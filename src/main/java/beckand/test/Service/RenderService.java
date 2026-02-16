package beckand.test.Service;

import com.jogamp.opengl.*;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final int FPS = 60;
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
    private volatile boolean isRendering = false;
    private final Object renderLock = new Object();
    private volatile boolean renderComplete = false;
    private volatile boolean isInitialized = false;
    private ByteBuffer pixelBuffer;
    private volatile boolean needsRender = true;
    private String currentModelId = null;
    private float centerX = 0, centerY = 0, centerZ = 0;
    private volatile int highQualityFrames = 0;

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

            int width = Math.max(1, Math.min(MAX_RENDER_SIZE, renderWidth > 0 ? renderWidth : 1280));
            int height = Math.max(1, Math.min(MAX_RENDER_SIZE, renderHeight > 0 ? renderHeight : 720));
            renderWidth = width;
            renderHeight = height;
            ensurePixelBufferCapacity();
            stubMode = false;

            GLProfile profile = GLProfile.get(GLProfile.GL2);
            if (profile == null) throw new RuntimeException("GL2 profile is not available");

            GLCapabilities capabilities = new GLCapabilities(profile);
            capabilities.setHardwareAccelerated(true);
            capabilities.setDoubleBuffered(true);
            capabilities.setDepthBits(24);
            capabilities.setSampleBuffers(true);
            capabilities.setNumSamples(8);

            GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            drawable = factory.createOffscreenAutoDrawable(null, capabilities, null, renderWidth, renderHeight);

            drawable.addGLEventListener(new GLEventListener() {
                @Override
                public void init(GLAutoDrawable d) {
                    GL gl = d.getGL();
                    if (!(gl instanceof GL2 gl2)) throw new RuntimeException("GL2 is not available");
                    gl2.glClearColor(0.06f, 0.06f, 0.08f, 1.0f);
                    gl2.glEnable(GL2.GL_DEPTH_TEST);
                    gl2.glEnable(GL2.GL_LIGHTING);
                    gl2.glEnable(GL2.GL_NORMALIZE);
                    gl2.glShadeModel(GL2.GL_SMOOTH);
                    gl2.glEnable(GL2.GL_LIGHT0);
                    gl2.glEnable(GL2.GL_LIGHT1);
                    gl2.glEnable(GL2.GL_COLOR_MATERIAL);
                    gl2.glColorMaterial(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE);
                    float[] light0Pos = {1.2f, 1.0f, 1.0f, 0.0f};
                    float[] light0Amb = {0.25f, 0.25f, 0.28f, 1.0f};
                    float[] light0Diff = {0.85f, 0.85f, 0.88f, 1.0f};
                    float[] light0Spec = {1.0f, 1.0f, 1.0f, 1.0f};
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, light0Pos, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT, light0Amb, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, light0Diff, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_SPECULAR, light0Spec, 0);
                    float[] light1Pos = {-0.8f, 0.5f, 1.0f, 0.0f};
                    float[] light1Amb = {0.08f, 0.08f, 0.1f, 1.0f};
                    float[] light1Diff = {0.35f, 0.35f, 0.4f, 1.0f};
                    gl2.glLightfv(GL2.GL_LIGHT1, GL2.GL_POSITION, light1Pos, 0);
                    gl2.glLightfv(GL2.GL_LIGHT1, GL2.GL_AMBIENT, light1Amb, 0);
                    gl2.glLightfv(GL2.GL_LIGHT1, GL2.GL_DIFFUSE, light1Diff, 0);
                    isInitialized = true;
                }

                @Override
                public void dispose(GLAutoDrawable d) { isInitialized = false; }

                @Override
                public void display(GLAutoDrawable d) {
                    if (!isInitialized) return;
                    GL gl = d.getGL();
                    if (!(gl instanceof GL2 gl2)) return;
                    if (Math.abs(currentAzimuth - targetAzimuth) > 0.01f || Math.abs(currentElevation - targetElevation) > 0.01f)
                        needsRender = true;
                    if (!needsRender) return;

                    gl2.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
                    float delta = 0.2f;
                    currentAzimuth += (targetAzimuth - currentAzimuth) * delta;
                    currentElevation += (targetElevation - currentElevation) * delta;
                    while (currentAzimuth > 360) currentAzimuth -= 360;
                    while (currentAzimuth < 0) currentAzimuth += 360;
                    currentElevation = Math.max(-80, Math.min(80, currentElevation));

                    gl2.glMatrixMode(GL2.GL_PROJECTION);
                    gl2.glLoadIdentity();
                    glu.gluPerspective(45.0, (double) renderWidth / renderHeight, 0.1, 100.0);
                    gl2.glMatrixMode(GL2.GL_MODELVIEW);
                    gl2.glLoadIdentity();

                    double radius = 20.0;
                    double az = Math.toRadians(currentAzimuth);
                    double el = Math.toRadians(currentElevation);
                    double x = radius * Math.cos(el) * Math.sin(az);
                    double y = radius * Math.sin(el);
                    double z = radius * Math.cos(el) * Math.cos(az);
                    glu.gluLookAt(x, y, z, centerX, centerY, centerZ, 0, 1, 0);

                    if (currentModel != null) {
                        gl2.glPushMatrix();
                        gl2.glTranslatef(-centerX, -centerY, -centerZ);
                        float[] matAmb = {0.4f, 0.4f, 0.45f, 1.0f};
                        float[] matDiff = {0.75f, 0.75f, 0.8f, 1.0f};
                        float[] matSpec = {0.35f, 0.35f, 0.4f, 1.0f};
                        float[] matShine = {32.0f};
                        gl2.glMaterialfv(GL2.GL_FRONT_AND_BACK, GL2.GL_AMBIENT, matAmb, 0);
                        gl2.glMaterialfv(GL2.GL_FRONT_AND_BACK, GL2.GL_DIFFUSE, matDiff, 0);
                        gl2.glMaterialfv(GL2.GL_FRONT_AND_BACK, GL2.GL_SPECULAR, matSpec, 0);
                        gl2.glMaterialfv(GL2.GL_FRONT_AND_BACK, GL2.GL_SHININESS, matShine, 0);
                        gl2.glBegin(GL2.GL_TRIANGLES);
                        for (int i = 0; i < currentModel.getNumFaces(); i++) {
                            ObjFace face = currentModel.getFace(i);
                            if (face.getNumVertices() == 3) {
                                float[] vx = new float[3], vy = new float[3], vz = new float[3];
                                for (int j = 0; j < 3; j++) {
                                    int vi = face.getVertexIndex(j);
                                    FloatTuple v = currentModel.getVertex(vi);
                                    vx[j] = v.getX(); vy[j] = v.getY(); vz[j] = v.getZ();
                                }
                                float ex1 = vx[1] - vx[0], ey1 = vy[1] - vy[0], ez1 = vz[1] - vz[0];
                                float ex2 = vx[2] - vx[0], ey2 = vy[2] - vy[0], ez2 = vz[2] - vz[0];
                                float nx = ey1 * ez2 - ez1 * ey2, ny = ez1 * ex2 - ex1 * ez2, nz = ex1 * ey2 - ey1 * ex2;
                                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                                if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
                                for (int j = 0; j < 3; j++) {
                                    if (currentModel.getNumNormals() > 0) {
                                        int nidx = face.getNormalIndex(j);
                                        if (nidx >= 0) {
                                            FloatTuple n = currentModel.getNormal(nidx);
                                            gl2.glNormal3f(n.getX(), n.getY(), n.getZ());
                                        } else {
                                            gl2.glNormal3f(nx, ny, nz);
                                        }
                                    } else {
                                        gl2.glNormal3f(nx, ny, nz);
                                    }
                                    gl2.glVertex3f(vx[j], vy[j], vz[j]);
                                }
                            }
                        }
                        gl2.glEnd();
                        gl2.glPopMatrix();
                    }

                    gl2.glReadBuffer(GL.GL_BACK);
                    gl2.glReadPixels(0, 0, renderWidth, renderHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixelBuffer);
                    pixelBuffer.rewind();
                    needsRender = false;
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
            animator = new FPSAnimator(drawable, FPS);
            animator.start();

            long start = System.currentTimeMillis();
            while (!isInitialized && System.currentTimeMillis() - start < 5000) Thread.sleep(100);
            if (!isInitialized) throw new RuntimeException("Failed to initialize OpenGL context");
        } catch (Throwable e) {
            // Catch Error too (e.g. UnsatisfiedLinkError when X11/GL libs missing in Docker)
            log.warn("OpenGL init failed, using stub: {}", e.toString());
            stubMode = true;
        }
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

    private void updateModelBounds() {
        if (currentModel == null) return;
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
    }

    private int quantizeAngle(double angle) {
        int step = Math.max(1, angleStepDeg);
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
        String cacheKey = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight;
        byte[] cached = renderCache.get(cacheKey);
        if (cached != null) return cached;

        if (!objectKey.equals(currentModelId)) {
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            currentModelId = objectKey;
            updateModelBounds();
        }

        targetAzimuth = qAz;
        targetElevation = qEl;
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
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < renderHeight; i++) {
            for (int j = 0; j < renderWidth; j++) {
                int idx = (renderHeight - 1 - i) * renderWidth + j;
                int r = pixelBuffer.get(idx * 4) & 0xFF;
                int g = pixelBuffer.get(idx * 4 + 1) & 0xFF;
                int b = pixelBuffer.get(idx * 4 + 2) & 0xFF;
                pixels[i * renderWidth + j] = (r << 16) | (g << 8) | b;
            }
        }
        byte[] out = encodeJpeg(image, jpegQuality);
        if (renderCache.size() >= maxCacheEntries) {
            Iterator<String> it = renderCache.keySet().iterator();
            if (it.hasNext()) renderCache.remove(it.next());
        }
        renderCache.put(cacheKey, out);
        return out;
    }

    public byte[] renderModelAdaptive(String objectKey, InputStream modelStream, String fileType, double azimuth, double elevation, boolean finalFrame) throws IOException {
        if (stubMode || !isInitialized) return renderStubJpeg(objectKey, azimuth, elevation);
        ensurePixelBufferCapacity();
        int qAz = quantizeAngle(azimuth);
        int qEl = quantizeAngle(elevation);
        if (finalFrame) {
            String key = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight;
            byte[] cached = renderCache.get(key);
            if (cached != null) return cached;
        }

        if (!objectKey.equals(currentModelId)) {
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            currentModelId = objectKey;
            updateModelBounds();
        }

        targetAzimuth = qAz;
        targetElevation = qEl;
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

        BufferedImage full = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) full.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < renderHeight; i++) {
            for (int j = 0; j < renderWidth; j++) {
                int idx = (renderHeight - 1 - i) * renderWidth + j;
                int r = pixelBuffer.get(idx * 4) & 0xFF;
                int g = pixelBuffer.get(idx * 4 + 1) & 0xFF;
                int b = pixelBuffer.get(idx * 4 + 2) & 0xFF;
                pixels[i * renderWidth + j] = (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage toEncode = full;
        float quality = finalFrame ? Math.max(0.85f, jpegQuality) : Math.min(0.7f, Math.max(0.4f, jpegQuality));
        if (!finalFrame) {
            int w = Math.max(1, renderWidth / 2);
            int h = Math.max(1, renderHeight / 2);
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(full, 0, 0, w, h, null);
            g2.dispose();
            toEncode = scaled;
        }
        byte[] out = encodeJpeg(toEncode, quality);
        if (finalFrame) {
            String key = objectKey + ":" + qAz + ":" + qEl + ":" + renderWidth + "x" + renderHeight;
            if (renderCache.size() >= maxCacheEntries) {
                Iterator<String> it = renderCache.keySet().iterator();
                if (it.hasNext()) renderCache.remove(it.next());
            }
            renderCache.put(key, out);
        }
        return out;
    }

    public void loadModelIfNeeded(String objectKey, InputStream modelStream) throws IOException {
        if (!objectKey.equals(currentModelId)) {
            currentModel = ObjReader.read(modelStream);
            if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0)
                throw new IOException("Модель не содержит вершин или граней");
            currentModelId = objectKey;
            updateModelBounds();
            needsRender = true;
            highQualityFrames = Math.max(highQualityFrames, 3);
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
                    try { renderLock.wait(10); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted", e);
                    }
                }
            }
        }
        BufferedImage full = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) full.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < renderHeight; i++) {
            for (int j = 0; j < renderWidth; j++) {
                int idx = (renderHeight - 1 - i) * renderWidth + j;
                int r = pixelBuffer.get(idx * 4) & 0xFF;
                int g = pixelBuffer.get(idx * 4 + 1) & 0xFF;
                int b = pixelBuffer.get(idx * 4 + 2) & 0xFF;
                pixels[i * renderWidth + j] = (r << 16) | (g << 8) | b;
            }
        }
        float quality = highQuality ? Math.max(0.85f, jpegQuality) : Math.min(0.7f, Math.max(0.4f, jpegQuality));
        return encodeJpeg(full, quality);
    }

    public void clearRenderCache() { renderCache.clear(); }
}
