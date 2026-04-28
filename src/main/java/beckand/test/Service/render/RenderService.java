package beckand.test.Service.render;

import com.jogamp.opengl.*;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import com.jogamp.nativewindow.AbstractGraphicsDevice;
import de.javagl.obj.FloatTuple;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RenderService {
    @Value("${render.width:1280}")
    private int renderWidth;
    @Value("${render.height:720}")
    private int renderHeight;
    @Value("${render.jpeg.quality:0.92}")
    private float jpegQuality;
    @Value("${render.angle.step.deg:2}")
    private int angleStepDeg;
    @Value("${render.cache.max.entries:200}")
    private int maxCacheEntries;
    @Value("${render.stream.fps:20}")
    private int renderStreamFps;
    /** 0 отключает MSAA — нужно для Mesa/llvmpipe в Docker; для GPU можно 4–8 */
    @Value("${render.gl.samples:0}")
    private int renderGlSamples;
    /**
     * Доля размера GL-кадра для JPEG при вращении (final=false). 1.0 = полный размер, выше нагрузка.
     * Раньше было жёстко 0.5.
     */
    @Value("${render.preview.scale:0.75}")
    private double previewScale;
    /** Качество JPEG для превью при вращении (0.35–0.95). Финальный кадр по-прежнему от render.jpeg.quality. */
    @Value("${render.preview.quality:0.84}")
    private float previewJpegQuality;
    /** 0 = без лимита. Иначе отказ в загрузке при превышении (защита софтверного GL от зависаний). */
    @Value("${render.max-triangle-count:0}")
    private long maxTriangleCount;
    /** Отсечение невидимых задних граней — заметно дешевле для закрытых мешей. */
    @Value("${render.back-face-culling:true}")
    private boolean backFaceCulling;
    /** При большом числе треугольников — ужать превью и шаг угла (только final=false), чтобы не лагало. */
    @Value("${render.adaptive-heavy-model:true}")
    private boolean adaptiveHeavyModel;

    private static final int MAX_RENDER_SIZE = 2048;

    private final Map<String, byte[]> renderCache = new ConcurrentHashMap<>();
    private GLAutoDrawable drawable;
    private FPSAnimator animator;
    private GLU glu;
    private Obj currentModel;
    private float currentAzimuth = 0;
    private float currentElevation = 0;
    private float targetAzimuth = 0;
    private float targetElevation = 0;
    private final Object renderLock = new Object();
    private volatile boolean renderComplete = false;
    private volatile boolean isInitialized = false;
    private ByteBuffer pixelBuffer;
    private volatile boolean needsRender = true;
    private String currentModelId = null;
    private float centerX = 0, centerY = 0, centerZ = 0;
    /** Половина диагонали AABB после центрирования — для дистанции камеры и frustum. */
    private float modelBoundingRadius = 1f;
    /** Треугольников после триангуляции (как VBO); для адаптивного превью и шага угла. */
    private volatile long loadedModelTriangleCount = 0;
    /** Множитель дистанции камеры (1 = по умолчанию; меньше — ближе, больше — дальше). Поле zoom в WebSocket. */
    private volatile double cameraDistanceScale = 1.0;
    private volatile int highQualityFrames = 0;
    private volatile long framesRendered = 0;
    private volatile boolean glInfoLogged = false;

    // --- GPU buffers (VBO) ---
    // Interleaved layout per-vertex: nx, ny, nz, x, y, z (6 floats)
    private static final int FLOATS_PER_VERTEX = 6;
    private static final int BYTES_PER_FLOAT = 4;
    private static final int VBO_STRIDE_BYTES = FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
    private volatile Obj pendingUploadModel = null;
    private volatile boolean vboDirty = false;
    private int vboId = 0;
    private int vboVertexCount = 0;

    @Autowired(required = false)
    private MinioClient minioClient;

    @Value("${minio.bucket:my-files}")
    private String bucket;

    private boolean stubMode = false;

    @PostConstruct
    private void initializeRenderer() {
        try {
            System.setProperty("java.awt.headless", "true");
            GLProfile.initSingleton();
            boolean useXvfb = !"0".equals(System.getenv("USE_XVFB"));
            log.info("Render init: USE_XVFB={} DISPLAY='{}' __EGL_VENDOR_LIBRARY_FILENAMES='{}'",
                    useXvfb ? "1" : "0",
                    System.getenv("DISPLAY"),
                    System.getenv("__EGL_VENDOR_LIBRARY_FILENAMES"));

            int width = Math.max(1, Math.min(MAX_RENDER_SIZE, renderWidth > 0 ? renderWidth : 1280));
            int height = Math.max(1, Math.min(MAX_RENDER_SIZE, renderHeight > 0 ? renderHeight : 720));
            renderWidth = width;
            renderHeight = height;
            ensurePixelBufferCapacity();
            stubMode = false;

            GLProfile profile = GLProfile.get(GLProfile.GL2);
            if (profile == null) throw new RuntimeException("GL2 profile is not available");

            GLCapabilities capabilities = new GLCapabilities(profile);
            boolean softwareGl = "1".equals(System.getenv("LIBGL_ALWAYS_SOFTWARE"));
            capabilities.setHardwareAccelerated(!softwareGl);
            capabilities.setDoubleBuffered(true);
            capabilities.setDepthBits(24);
            int samples = Math.max(0,Math.min(16, renderGlSamples));
            capabilities.setSampleBuffers(samples > 0);
            capabilities.setNumSamples(samples > 0 ? samples : 0);
            capabilities.setOnscreen(false);
            log.info("GL capabilities: hardwareAccelerated={} msaaSamples={}", !softwareGl, samples);

            GLDrawableFactory factory = null;
            try {
                log.info("Attempting EGL offscreen context...");
                Class<?> eglClass;
                try {
                    eglClass = Class.forName("com.jogamp.opengl.egl.EGLDrawableFactory");
                } catch (ClassNotFoundException ignored) {
                    eglClass = Class.forName("jogamp.opengl.egl.EGLDrawableFactory");
                }

                java.lang.reflect.Method getFactory = eglClass.getMethod("getEGLFactory");
                factory = (GLDrawableFactory) getFactory.invoke(null);
                if (factory != null) {
                    AbstractGraphicsDevice device = factory.getDefaultDevice();
                    DefaultGLCapabilitiesChooser chooser = new DefaultGLCapabilitiesChooser();
                    drawable = factory.createOffscreenAutoDrawable(device, capabilities, chooser, renderWidth, renderHeight);
                    log.info("Using EGL for headless GPU rendering");
                }
            } catch (Throwable e) {
                log.warn("EGL not available, falling back to default offscreen: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                if (log.isDebugEnabled()) log.debug("EGL init failure", e);
            }
            if (drawable == null) {
                if ("0".equals(System.getenv("USE_XVFB"))) {
                    throw new RuntimeException("EGL init failed and USE_XVFB=0, refusing X11/GLX fallback");
                }
                factory = GLDrawableFactory.getFactory(profile);
                drawable = factory.createOffscreenAutoDrawable(null, capabilities, null, renderWidth, renderHeight);
            }

            drawable.addGLEventListener(new GLEventListener() {
                @Override
                public void init(GLAutoDrawable d) {
                    GL gl = d.getGL();
                    if (!(gl instanceof GL2 gl2)) throw new RuntimeException("GL2 is not available");
                    logGlInfoOnce(gl2);
                    gl2.glClearColor(0.06f, 0.06f, 0.08f, 1.0f);
                    gl2.glEnable(GL2.GL_DEPTH_TEST);
                    if (backFaceCulling) {
                        gl2.glEnable(GL2.GL_CULL_FACE);
                        gl2.glCullFace(GL.GL_BACK);
                        gl2.glFrontFace(GL.GL_CCW);
                    } else {
                        gl2.glDisable(GL2.GL_CULL_FACE);
                    }
                    gl2.glEnable(GL2.GL_LIGHTING);
                    gl2.glEnable(GL2.GL_NORMALIZE);
                    gl2.glShadeModel(GL2.GL_SMOOTH);
                    gl2.glEnable(GL2.GL_LIGHT0);
                    gl2.glEnable(GL2.GL_LIGHT1);
                    gl2.glEnable(GL2.GL_COLOR_MATERIAL);
                    gl2.glColorMaterial(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
                    isInitialized = true;
                }

                @Override
                public void dispose(GLAutoDrawable d) {
                    try {
                        GL gl = d.getGL();
                        if (gl instanceof GL2 gl2) {
                            deleteVbo(gl2);
                        }
                    } catch (Throwable ignored) { }
                    isInitialized = false;
                }

                @Override
                public void display(GLAutoDrawable d) {
                    if (!isInitialized) return;
                    GL gl = d.getGL();
                    if (!(gl instanceof GL2 gl2)) return;
                    if (!glInfoLogged) logGlInfoOnce(gl2);
                    if (Math.abs(currentAzimuth - targetAzimuth) > 0.01f || Math.abs(currentElevation - targetElevation) > 0.01f)
                        needsRender = true;
                    if (!needsRender) return;

                    long t0 = System.nanoTime();
                    gl2.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
                    float delta = 0.2f;
                    currentAzimuth += (targetAzimuth - currentAzimuth) * delta;
                    currentElevation += (targetElevation - currentElevation) * delta;
                    while (currentAzimuth > 360) currentAzimuth -= 360;
                    while (currentAzimuth < 0) currentAzimuth += 360;
                    currentElevation = Math.max(-80, Math.min(80, currentElevation));

                    // Upload model VBO on GL thread (safe) when model changes
                    if (vboDirty && pendingUploadModel != null) {
                        try {
                            uploadModelToVbo(gl2, pendingUploadModel);
                            pendingUploadModel = null;
                            vboDirty = false;
                        } catch (Throwable e) {
                            // If VBO upload fails, fall back to stub (better than hard crash)
                            log.warn("VBO upload failed, switching to stub: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                            stubMode = true;
                            return;
                        }
                    }

                    gl2.glMatrixMode(GL2.GL_PROJECTION);
                    gl2.glLoadIdentity();
                    double camDistBase = Math.max(modelBoundingRadius * 2.8, 0.15);
                    double camDist = camDistBase * cameraDistanceScale;
                    double zNear = Math.max(camDist * 0.008, 0.01);
                    double zFar = Math.max(camDist * 50.0, modelBoundingRadius * 30.0 + 50.0);
                    glu.gluPerspective(45.0, (double) renderWidth / renderHeight, zNear, zFar);
                    gl2.glMatrixMode(GL2.GL_MODELVIEW);
                    gl2.glLoadIdentity();

                    double az = Math.toRadians(currentAzimuth);
                    double el = Math.toRadians(currentElevation);
                    double x = camDist * Math.cos(el) * Math.sin(az);
                    double y = camDist * Math.sin(el);
                    double z = camDist * Math.cos(el) * Math.cos(az);
                    // После glTranslate(-center) модель в начале координат — смотреть на (0,0,0), не на centerX/Y/Z в OBJ.
                    glu.gluLookAt(x, y, z, 0, 0, 0, 0, 1, 0);

                    if (currentModel != null && vboId != 0 && vboVertexCount > 0) {
                        gl2.glPushMatrix();
                        gl2.glTranslatef(-centerX, -centerY, -centerZ);
                        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, vboId);
                        gl2.glEnableClientState(GL2.GL_NORMAL_ARRAY);
                        gl2.glEnableClientState(GL2.GL_VERTEX_ARRAY);
                        gl2.glNormalPointer(GL.GL_FLOAT, VBO_STRIDE_BYTES, 0L);
                        gl2.glVertexPointer(3, GL.GL_FLOAT, VBO_STRIDE_BYTES, (long) (3 * BYTES_PER_FLOAT));
                        gl2.glDrawArrays(GL.GL_TRIANGLES, 0, vboVertexCount);
                        gl2.glDisableClientState(GL2.GL_VERTEX_ARRAY);
                        gl2.glDisableClientState(GL2.GL_NORMAL_ARRAY);
                        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
                        gl2.glPopMatrix();
                    }

                    gl2.glReadBuffer(GL.GL_BACK);
                    gl2.glReadPixels(0, 0, renderWidth, renderHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixelBuffer);
                    pixelBuffer.rewind();
                    needsRender = false;
                    framesRendered++;
                    if (framesRendered == 1 || framesRendered % 120 == 0) {
                        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                        log.debug("Render frame done: {}x{} ms={} az={} el={} faces={} verts={} vboVerts={}",
                                renderWidth, renderHeight, ms, currentAzimuth, currentElevation,
                                currentModel != null ? currentModel.getNumFaces() : 0,
                                currentModel != null ? currentModel.getNumVertices() : 0,
                                vboVertexCount);
                    }
                    synchronized (renderLock) {
                        renderComplete = true;
                        renderLock.notifyAll();
                    }
                }

                @Override
                public void reshape(GLAutoDrawable d, int x, int y, int width, int height) {
                    GL gl = d.getGL();
                    if (gl instanceof GL2 gl2) gl2.glViewport(0, 0, width, height);
                }
            });

            glu = new GLU();
            int fps = Math.max(1, Math.min(60, renderStreamFps));
            animator = new FPSAnimator(drawable, fps);
            animator.start();
            log.info("Render animator started at {} FPS ({}x{})", fps, renderWidth, renderHeight);

            long start = System.currentTimeMillis();
            while (!isInitialized && System.currentTimeMillis() - start < 5000) Thread.sleep(100);
            if (!isInitialized) throw new RuntimeException("Failed to initialize OpenGL context");
        } catch (Throwable e) {
            log.warn("OpenGL init failed, using stub: {}", e.toString());
            stubMode = true;
        }
    }

    private void logGlInfoOnce(GL2 gl2) {
        if (glInfoLogged) return;
        glInfoLogged = true;
        String vendor = safeGlString(gl2, GL2.GL_VENDOR);
        String renderer = safeGlString(gl2, GL2.GL_RENDERER);
        String version = safeGlString(gl2, GL2.GL_VERSION);
        String sl = safeGlString(gl2, GL2.GL_SHADING_LANGUAGE_VERSION);
        log.info("OpenGL context initialized. vendor='{}' renderer='{}' version='{}' glsl='{}'",
                vendor, renderer, version, sl);
    }

    private String safeGlString(GL2 gl2, int what) {
        try { return gl2.glGetString(what); } catch (Throwable t) { return null; }
    }

    @PreDestroy
    private void shutdownRenderer() {
        if (animator != null && animator.isStarted()) animator.stop();
        if (drawable != null) drawable.destroy();
    }

    private void ensurePixelBufferCapacity() {
        int size = Math.max(1, renderWidth) * Math.max(1, renderHeight) * 4;
        if (pixelBuffer == null || pixelBuffer.capacity() < size) {
            pixelBuffer = ByteBuffer.allocateDirect(size);
            pixelBuffer.order(ByteOrder.nativeOrder());
        }
    }

    private void deleteVbo(GL2 gl2) {
        if (vboId != 0) {
            try {
                gl2.glDeleteBuffers(1, new int[]{vboId}, 0);
            } catch (Throwable ignored) { }
            vboId = 0;
            vboVertexCount = 0;
        }
    }

    private void uploadModelToVbo(GL2 gl2, Obj model) {
        if (model == null) return;
        // (Re)create VBO for current model
        deleteVbo(gl2);
        FloatBuffer interleaved = buildInterleavedNormalPosBuffer(model);
        int verts = interleaved.remaining() / FLOATS_PER_VERTEX;
        if (verts <= 0) throw new IllegalStateException("No vertices for VBO");

        int[] ids = new int[1];
        gl2.glGenBuffers(1, ids, 0);
        vboId = ids[0];
        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, vboId);
        gl2.glBufferData(GL.GL_ARRAY_BUFFER, (long) interleaved.remaining() * BYTES_PER_FLOAT, interleaved, GL.GL_STATIC_DRAW);
        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        vboVertexCount = verts;
        log.info("VBO uploaded: id={} vertices={} (triangles={})", vboId, vboVertexCount, vboVertexCount / 3);
    }

    private FloatBuffer buildInterleavedNormalPosBuffer(Obj model) {
        // Conservative estimate: fan-triangulate polygons (n-2 triangles)
        long triCount = 0;
        for (int i = 0; i < model.getNumFaces(); i++) {
            ObjFace f = model.getFace(i);
            int n = f.getNumVertices();
            if (n >= 3) triCount += (long) (n - 2);
        }
        if (triCount <= 0) throw new IllegalStateException("Model has no drawable faces");

        long vertexCount = triCount * 3L;
        if (vertexCount > Integer.MAX_VALUE) throw new IllegalStateException("Model too large for VBO");

        int floats = Math.toIntExact(vertexCount * FLOATS_PER_VERTEX);
        ByteBuffer bb = ByteBuffer.allocateDirect((long) floats * BYTES_PER_FLOAT > Integer.MAX_VALUE ? Integer.MAX_VALUE : floats * BYTES_PER_FLOAT);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();

        for (int i = 0; i < model.getNumFaces(); i++) {
            ObjFace face = model.getFace(i);
            int n = face.getNumVertices();
            if (n < 3) continue;
            // triangle fan: (0, k, k+1)
            int i0 = face.getVertexIndex(0);
            FloatTuple v0 = model.getVertex(i0);
            for (int k = 1; k + 1 < n; k++) {
                FloatTuple v1 = model.getVertex(face.getVertexIndex(k));
                FloatTuple v2 = model.getVertex(face.getVertexIndex(k + 1));

                float ex1 = v1.getX() - v0.getX();
                float ey1 = v1.getY() - v0.getY();
                float ez1 = v1.getZ() - v0.getZ();
                float ex2 = v2.getX() - v0.getX();
                float ey2 = v2.getY() - v0.getY();
                float ez2 = v2.getZ() - v0.getZ();
                float nx = ey1 * ez2 - ez1 * ey2;
                float ny = ez1 * ex2 - ex1 * ez2;
                float nz = ex1 * ey2 - ey1 * ex2;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-6f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                } else {
                    nx = 0f;
                    ny = 1f;
                    nz = 0f;
                }

                putVertex(fb, nx, ny, nz, v0);
                putVertex(fb, nx, ny, nz, v1);
                putVertex(fb, nx, ny, nz, v2);
            }
        }
        fb.flip();
        return fb;
    }

    private void putVertex(FloatBuffer fb, float nx, float ny, float nz, FloatTuple v) {
        fb.put(nx).put(ny).put(nz);
        fb.put(v.getX()).put(v.getY()).put(v.getZ());
    }

    /** Число треугольников после триангуляции n-угольников (как в VBO). */
    private static long countTriangles(Obj model) {
        long triCount = 0;
        for (int i = 0; i < model.getNumFaces(); i++) {
            ObjFace f = model.getFace(i);
            int n = f.getNumVertices();
            if (n >= 3) triCount += (long) (n - 2);
        }
        return triCount;
    }

    private void validateTriangleBudget(Obj model) throws IOException {
        if (maxTriangleCount <= 0 || model == null) return;
        long n = countTriangles(model);
        if (n > maxTriangleCount) {
            throw new IOException(
                    "Слишком плотная сетка: " + n + " треугольников (лимит " + maxTriangleCount
                            + "). Упростите модель в Blender (Modifier → Decimate) или увеличьте render.max-triangle-count в настройках.");
        }
    }

    private void updateModelBounds() {
        if (currentModel == null) {
            loadedModelTriangleCount = 0;
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < currentModel.getNumVertices(); i++) {
            FloatTuple v = currentModel.getVertex(i);
            minX = Math.min(minX, v.getX());
            minY = Math.min(minY, v.getY());
            minZ = Math.min(minZ, v.getZ());
            maxX = Math.max(maxX, v.getX());
            maxY = Math.max(maxY, v.getY());
            maxZ = Math.max(maxZ, v.getZ());
        }
        centerX = (minX + maxX) / 2f;
        centerY = (minY + maxY) / 2f;
        centerZ = (minZ + maxZ) / 2f;
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float diag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        modelBoundingRadius = Math.max(diag * 0.5f, 1e-4f);
        loadedModelTriangleCount = countTriangles(currentModel);
        if (adaptiveHeavyModel && loadedModelTriangleCount > 400_000) {
            log.info("Тяжёлая сетка: {} тр. — при вращении включено адаптивное превью (меньше лагов)", loadedModelTriangleCount);
        }
    }

    private int effectiveAngleStepDeg() {
        if (!adaptiveHeavyModel) return Math.max(1, angleStepDeg);
        int base = Math.max(1, angleStepDeg);
        long n = loadedModelTriangleCount;
        if (n > 3_500_000L) return Math.max(base, 12);
        if (n > 2_000_000L) return Math.max(base, 8);
        if (n > 1_000_000L) return Math.max(base, 6);
        if (n > 500_000L) return Math.max(base, 4);
        if (n > 200_000L) return Math.max(base, 3);
        return base;
    }

    private double effectivePreviewScale(boolean finalFrame) {
        if (finalFrame || !adaptiveHeavyModel) {
            return Math.min(1.0, Math.max(0.25, previewScale));
        }
        double ps = Math.min(1.0, Math.max(0.25, previewScale));
        long n = loadedModelTriangleCount;
        if (n > 3_500_000L) return Math.min(ps, 0.26);
        if (n > 2_000_000L) return Math.min(ps, 0.34);
        if (n > 1_000_000L) return Math.min(ps, 0.45);
        if (n > 500_000L) return Math.min(ps, 0.58);
        if (n > 200_000L) return Math.min(ps, 0.72);
        return ps;
    }

    private float effectivePreviewJpegQuality(boolean finalFrame) {
        if (finalFrame) return Math.max(0.85f, jpegQuality);
        if (!adaptiveHeavyModel) return clampPreviewJpegQuality(previewJpegQuality);
        float q = clampPreviewJpegQuality(previewJpegQuality);
        long n = loadedModelTriangleCount;
        if (n > 3_500_000L) return Math.min(q, 0.52f);
        if (n > 2_000_000L) return Math.min(q, 0.62f);
        if (n > 1_000_000L) return Math.min(q, 0.72f);
        if (n > 500_000L) return Math.min(q, 0.8f);
        return q;
    }

    private int quantizeAngle(double angle) {
        int step = effectiveAngleStepDeg();
        int a = (int) Math.round(angle / step) * step;
        return Math.max(-360, Math.min(360, a));
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writers available");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            writer.dispose();
            return baos.toByteArray();
        }
    }

    private static float clampPreviewJpegQuality(float q) {
        return Math.min(0.95f, Math.max(0.35f, q));
    }

    /** GL readPixels — нижний ряд первый; в BufferedImage Y сверху вниз. */
    private void fillRgbFromGlReadBuffer(BufferedImage dst) {
        int w = renderWidth;
        int h = renderHeight;
        int[] pixels = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        ByteBuffer pb = pixelBuffer;
        for (int y = 0; y < h; y++) {
            int srcRow = (h - 1 - y) * w * 4;
            int dstRow = y * w;
            for (int x = 0; x < w; x++) {
                int s = srcRow + (x << 2);
                pixels[dstRow + x] = (pb.get(s) & 0xFF) << 16 | (pb.get(s + 1) & 0xFF) << 8 | (pb.get(s + 2) & 0xFF);
            }
        }
    }

    private byte[] renderStubJpeg(String objectKey, double azimuth, double elevation) throws IOException {
        BufferedImage bi = new BufferedImage(Math.max(1, renderWidth), Math.max(1, renderHeight), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(15, 15, 20));
        g.fillRect(0, 0, renderWidth, renderHeight);
        g.setColor(new java.awt.Color(180, 180, 190));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
        g.drawString("Stub Render", 20, 30);
        g.drawString("object: " + objectKey, 20, 55);
        g.drawString(String.format("az=%.1f el=%.1f", azimuth, elevation), 20, 75);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpeg", baos);
        return baos.toByteArray();
    }

    public byte[] renderModel(String objectKey, InputStream modelStream, String fileType, double azimuth, double elevation) throws IOException {
        if (stubMode || !isInitialized) return renderStubJpeg(objectKey, azimuth, elevation);
        ensurePixelBufferCapacity();
        int qAz = quantizeAngle(azimuth);
        int qEl = quantizeAngle(elevation);
        String cacheKey = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight + zoomCacheSuffix();
        byte[] cached = renderCache.get(cacheKey);
        if (cached != null) return cached;

        if (!objectKey.equals(currentModelId)) {
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            validateTriangleBudget(currentModel);
            currentModelId = objectKey;
            updateModelBounds();
            pendingUploadModel = currentModel;
            vboDirty = true;
            renderCache.clear();
        }

        targetAzimuth = qAz;
        targetElevation = qEl;
        currentAzimuth = qAz;
        currentElevation = qEl;
        while (currentAzimuth > 360f) currentAzimuth -= 360f;
        while (currentAzimuth < 0f) currentAzimuth += 360f;
        currentElevation = Math.max(-80f, Math.min(80f, currentElevation));

        needsRender = true;
        synchronized (renderLock) {
            renderComplete = false;
            while (!renderComplete) {
                try { renderLock.wait(10); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
        }

        BufferedImage image = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        fillRgbFromGlReadBuffer(image);
        byte[] out = encodeJpeg(image, jpegQuality);
        if (renderCache.size() >= maxCacheEntries) {
            Iterator<String> it = renderCache.keySet().iterator();
            if (it.hasNext()) renderCache.remove(it.next());
        }
        renderCache.put(cacheKey, out);
        return out;
    }

    /** Множитель дистанции камеры для серверного рендера (клиент: zoom в WebSocket). */
    public void setCameraDistanceScale(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale)) return;
        if (scale < 0.2) scale = 0.2;
        if (scale > 5.0) scale = 5.0;
        this.cameraDistanceScale = scale;
    }

    private String zoomCacheSuffix() {
        return ":z" + Math.round(cameraDistanceScale * 100.0);
    }

    /** Модель уже в памяти и на GPU — повторно тянуть объект из MinIO не нужно (снижает нагрузку на S3). */
    public boolean isModelLoaded(String objectKey) {
        return objectKey != null && objectKey.equals(currentModelId) && currentModel != null && !stubMode && isInitialized;
    }

    public byte[] renderModelAdaptive(String objectKey, InputStream modelStream, String fileType, double azimuth, double elevation, boolean finalFrame) throws IOException {
        if (stubMode || !isInitialized) return renderStubJpeg(objectKey, azimuth, elevation);
        long tAll0 = System.nanoTime();
        ensurePixelBufferCapacity();
        int qAz = quantizeAngle(azimuth);
        int qEl = quantizeAngle(elevation);

        if (finalFrame) {
            String key = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight + zoomCacheSuffix();
            byte[] cached = renderCache.get(key);
            if (cached != null) return cached;
        }

        if (!objectKey.equals(currentModelId)) {
            if (modelStream == null) {
                throw new IOException("Поток модели обязателен при первой загрузке объекта: " + objectKey);
            }
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            validateTriangleBudget(currentModel);
            currentModelId = objectKey;
            updateModelBounds();
            pendingUploadModel = currentModel;
            vboDirty = true;
            renderCache.clear();
        }

        targetAzimuth = qAz;
        targetElevation = qEl;
        // Один кадр на запрос: без мгновенного совпадения current≈target сглаживание (20%/тик) даёт кадр «не там»
        // и визуальные рывки при следующем target. Для JPEG по WS камера должна совпадать с квантованным углом.
        currentAzimuth = qAz;
        currentElevation = qEl;
        while (currentAzimuth > 360f) currentAzimuth -= 360f;
        while (currentAzimuth < 0f) currentAzimuth += 360f;
        currentElevation = Math.max(-80f, Math.min(80f, currentElevation));

        needsRender = true;
        synchronized (renderLock) {
            renderComplete = false;
            while (!renderComplete) {
                try {
                    renderLock.wait(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
        }

        BufferedImage full = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        fillRgbFromGlReadBuffer(full);

        BufferedImage toEncode = full;
        float quality = effectivePreviewJpegQuality(finalFrame);
        if (!finalFrame) {
            double scale = effectivePreviewScale(false);
            int w = Math.max(1, (int) Math.round(renderWidth * scale));
            int h = Math.max(1, (int) Math.round(renderHeight * scale));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(full, 0, 0, w, h, null);
            g2.dispose();
            toEncode = scaled;
        }

        byte[] out = encodeJpeg(toEncode, quality);
        if (finalFrame) {
            String key = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight + zoomCacheSuffix();
            if (renderCache.size() >= maxCacheEntries) {
                Iterator<String> it = renderCache.keySet().iterator();
                if (it.hasNext()) renderCache.remove(it.next());
            }
            renderCache.put(key, out);
        }

        long allMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tAll0);
        log.debug("Render adaptive: id={} final={} totalMs={} outBytes={} az={} el={}",
                objectKey, finalFrame, allMs, out.length, qAz, qEl);

        return out;
    }

    public void loadModelIfNeeded(String objectKey, InputStream modelStream) throws IOException {
        if (!objectKey.equals(currentModelId)) {
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            validateTriangleBudget(currentModel);
            currentModelId = objectKey;
            updateModelBounds();
            needsRender = true;
            highQualityFrames = Math.max(highQualityFrames, 3);
            pendingUploadModel = currentModel;
            vboDirty = true;
            renderCache.clear();
        }
    }

    public void updateAngles(double azimuth, double elevation, boolean highQualityNext) {
        targetAzimuth = quantizeAngle(azimuth);
        targetElevation = quantizeAngle(elevation);
        needsRender = true;
        if (highQualityNext) highQualityFrames = Math.max(highQualityFrames, 3);
    }

    public byte[] grabEncodedFrame() throws IOException {
        if (stubMode || !isInitialized)
            return renderStubJpeg(currentModelId != null ? currentModelId : "stub", currentAzimuth, currentElevation);
        boolean highQuality = highQualityFrames > 0;
        if (highQualityFrames > 0) highQualityFrames--;

        if (needsRender) {
            synchronized (renderLock) {
                long start = System.currentTimeMillis();
                while (!renderComplete && System.currentTimeMillis() - start < 500) {
                    try {
                        renderLock.wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted", e);
                    }
                }
            }
        }

        BufferedImage full = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        fillRgbFromGlReadBuffer(full);

        float quality = highQuality ? Math.max(0.85f, jpegQuality) : effectivePreviewJpegQuality(false);
        return encodeJpeg(full, quality);
    }

    public void clearRenderCache() {
        renderCache.clear();
    }
}

