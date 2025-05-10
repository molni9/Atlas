package beckand.test.Service;

import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjFace;
import de.javagl.obj.FloatTuple;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RenderService {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int FPS = 60;

    private final Map<String, Obj> modelCache = new ConcurrentHashMap<>();
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

    public RenderService() {
        log.info("Initializing RenderService...");
        try {
            // Настройка headless режима
            log.info("Setting up headless mode...");
            System.setProperty("java.awt.headless", "true");
            System.setProperty("jogamp.gluegen.UseTempJarCache", "false");
            System.setProperty("jogamp.gluegen.UseTempJarCache", "false");
            System.setProperty("jogamp.gluegen.UseTempJarCache", "false");
            
            // Инициализация OpenGL
            log.info("Initializing OpenGL...");
            GLProfile.initSingleton();
            
            // Получение профиля GL2
            log.info("Getting OpenGL profile...");
            GLProfile profile = GLProfile.get(GLProfile.GL2);
            if (profile == null) {
                log.error("GL2 profile is not available");
                throw new RuntimeException("GL2 profile is not available");
            }
            
            log.info("Using OpenGL profile: {}", profile.getName());
            
            log.info("Setting up GLCapabilities...");
            GLCapabilities capabilities = new GLCapabilities(profile);
            capabilities.setHardwareAccelerated(true);
            capabilities.setDoubleBuffered(true);
            capabilities.setDepthBits(24);
            capabilities.setSampleBuffers(true);
            capabilities.setNumSamples(4);

            // Создание offscreen drawable
            log.info("Creating offscreen drawable...");
            GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            drawable = factory.createOffscreenAutoDrawable(
                    null,
                    capabilities,
                    null,
                    WIDTH,
                    HEIGHT
            );

            // Предварительно выделяем буфер для пикселей
            log.info("Allocating pixel buffer...");
            pixelBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            pixelBuffer.order(ByteOrder.nativeOrder());

            log.info("Adding GLEventListener...");
            drawable.addGLEventListener(new GLEventListener() {
                @Override
                public void init(GLAutoDrawable drawable) {
                    log.info("Initializing GL context...");
                    try {
                        GL gl = drawable.getGL();
                        if (!(gl instanceof GL2)) {
                            log.error("GL2 is not available");
                            throw new RuntimeException("GL2 is not available");
                        }
                        GL2 gl2 = gl.getGL2();
                        
                        log.info("Setting up GL context...");
                        gl2.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
                        gl2.glEnable(GL2.GL_DEPTH_TEST);
                        gl2.glEnable(GL2.GL_LIGHTING);
                        gl2.glEnable(GL2.GL_LIGHT0);
                        gl2.glEnable(GL2.GL_COLOR_MATERIAL);
                        float[] lightPosition = {1.0f, 1.0f, 1.0f, 0.0f};
                        float[] lightAmbient = {0.2f, 0.2f, 0.2f, 1.0f};
                        float[] lightDiffuse = {0.8f, 0.8f, 0.8f, 1.0f};
                        float[] lightSpecular = {1.0f, 1.0f, 1.0f, 1.0f};
                        gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, lightPosition, 0);
                        gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT, lightAmbient, 0);
                        gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, lightDiffuse, 0);
                        gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_SPECULAR, lightSpecular, 0);
                        isInitialized = true;
                        log.info("OpenGL context initialized successfully");
                    } catch (Exception e) {
                        log.error("Failed to initialize OpenGL context", e);
                        throw new RuntimeException("Failed to initialize OpenGL context", e);
                    }
                }

                @Override
                public void dispose(GLAutoDrawable drawable) {
                    log.info("Disposing GL context...");
                    isInitialized = false;
                }

                @Override
                public void display(GLAutoDrawable drawable) {
                    if (!isRendering || !isInitialized) return;

                    try {
                        GL gl = drawable.getGL();
                        if (!(gl instanceof GL2)) {
                            log.error("GL2 is not available in display");
                            return;
                        }
                        GL2 gl2 = gl.getGL2();
                        
                        gl2.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

                        // Плавное вращение
                        float rotationSpeed = 0.1f;
                        float azimuthDiff = targetAzimuth - currentAzimuth;
                        float elevationDiff = targetElevation - currentElevation;

                        while (azimuthDiff > 180) azimuthDiff -= 360;
                        while (azimuthDiff < -180) azimuthDiff += 360;

                        currentAzimuth += azimuthDiff * rotationSpeed;
                        currentElevation += elevationDiff * rotationSpeed;

                        while (currentAzimuth > 360) currentAzimuth -= 360;
                        while (currentAzimuth < 0) currentAzimuth += 360;
                        currentElevation = Math.max(-90, Math.min(90, currentElevation));

                        // Настройка проекции
                        gl2.glMatrixMode(GL2.GL_PROJECTION);
                        gl2.glLoadIdentity();
                        glu.gluPerspective(45.0, (double) WIDTH / HEIGHT, 0.1, 100.0);

                        // Настройка камеры
                        gl2.glMatrixMode(GL2.GL_MODELVIEW);
                        gl2.glLoadIdentity();
                        double radius = 5.0;
                        double az = Math.toRadians(currentAzimuth);
                        double el = Math.toRadians(currentElevation);
                        double x = radius * Math.cos(el) * Math.sin(az);
                        double y = radius * Math.sin(el);
                        double z = radius * Math.cos(el) * Math.cos(az);
                        glu.gluLookAt(x, y, z, 0, 0, 0, 0, 1, 0);

                        // Отрисовка модели
                        if (currentModel != null) {
                            gl2.glPushMatrix();
                            
                            // Центрирование и масштабирование модели
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

                            float centerX = (minX + maxX) / 2f;
                            float centerY = (minY + maxY) / 2f;
                            float centerZ = (minZ + maxZ) / 2f;
                            float maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
                            float scale = 2.0f / maxDim;

                            gl2.glScalef(scale, scale, scale);
                            gl2.glTranslatef(-centerX, -centerY, -centerZ);

                            // Отрисовка треугольников
                            gl2.glColor3f(0.7f, 0.7f, 0.7f);
                            gl2.glBegin(GL2.GL_TRIANGLES);
                            for (int i = 0; i < currentModel.getNumFaces(); i++) {
                                ObjFace face = currentModel.getFace(i);
                                if (face.getNumVertices() == 3) {
                                    for (int j = 0; j < 3; j++) {
                                        int idx = face.getVertexIndex(j);
                                        FloatTuple vertex = currentModel.getVertex(idx);
                                        gl2.glVertex3f(vertex.getX(), vertex.getY(), vertex.getZ());
                                    }
                                }
                            }
                            gl2.glEnd();
                            gl2.glPopMatrix();
                        }

                        // Чтение пикселей
                        try {
                            gl2.glReadBuffer(GL.GL_BACK);
                            gl2.glReadPixels(0, 0, WIDTH, HEIGHT, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixelBuffer);
                            pixelBuffer.rewind();
                        } catch (Exception e) {
                            log.error("Error reading pixels", e);
                        }

                        synchronized (renderLock) {
                            renderComplete = true;
                            renderLock.notifyAll();
                        }
                    } catch (Exception e) {
                        log.error("Error during rendering", e);
                        synchronized (renderLock) {
                            renderComplete = true;
                            renderLock.notifyAll();
                        }
                    }
                }

                @Override
                public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
                    try {
                        GL gl = drawable.getGL();
                        if (!(gl instanceof GL2)) {
                            log.error("GL2 is not available in reshape");
                            return;
                        }
                        GL2 gl2 = gl.getGL2();
                        gl2.glViewport(0, 0, width, height);
                    } catch (Exception e) {
                        log.error("Error during reshape", e);
                    }
                }
            });

            log.info("Creating GLU...");
            glu = new GLU();
            
            log.info("Starting animator...");
            animator = new FPSAnimator(drawable, FPS);
            animator.start();

            // Ждем инициализации OpenGL контекста
            log.info("Waiting for OpenGL context initialization...");
            long startTime = System.currentTimeMillis();
            while (!isInitialized && System.currentTimeMillis() - startTime < 5000) {
                Thread.sleep(100);
            }

            if (!isInitialized) {
                log.error("Failed to initialize OpenGL context after timeout");
                throw new RuntimeException("Failed to initialize OpenGL context");
            }
            
            log.info("RenderService initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize OpenGL", e);
            throw new RuntimeException("Failed to initialize OpenGL: " + e.getMessage(), e);
        }
    }

    public byte[] renderModel(InputStream modelStream, String fileType, double azimuth, double elevation) throws IOException {
        if (!isInitialized) {
            throw new IOException("OpenGL context is not initialized");
        }

        if (fileType == null || !fileType.toLowerCase().contains("obj")) {
            throw new IOException("Поддерживаются только OBJ файлы. Получен тип: " + fileType);
        }

        try {
            // Загрузка или получение модели из кэша
            currentModel = modelCache.computeIfAbsent("current_model", key -> {
                try {
                    log.info("Loading new OBJ model");
                    Obj obj = ObjReader.read(modelStream);
                    if (obj.getNumFaces() == 0 || obj.getNumVertices() == 0) {
                        throw new IOException("Модель не содержит вершин или граней");
                    }
                    return obj;
                } catch (Exception e) {
                    log.error("Failed to load OBJ file", e);
                    throw new RuntimeException("Ошибка чтения OBJ: " + e.getMessage(), e);
                }
            });

            // Обновление целевых углов
            targetAzimuth = (float) azimuth;
            targetElevation = (float) elevation;
            isRendering = true;
            renderComplete = false;

            // Ожидание завершения рендеринга
            synchronized (renderLock) {
                long startTime = System.currentTimeMillis();
                while (!renderComplete && System.currentTimeMillis() - startTime < 5000) {
                    try {
                        renderLock.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Rendering was interrupted", e);
                    }
                }
            }

            // Создание изображения из буфера
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            
            for (int i = 0; i < HEIGHT; i++) {
                for (int j = 0; j < WIDTH; j++) {
                    int index = (HEIGHT - 1 - i) * WIDTH + j;
                    int r = pixelBuffer.get(index * 4) & 0xFF;
                    int g = pixelBuffer.get(index * 4 + 1) & 0xFF;
                    int b = pixelBuffer.get(index * 4 + 2) & 0xFF;
                    pixels[i * WIDTH + j] = (r << 16) | (g << 8) | b;
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("Error during render", e);
            throw new IOException("Error during render: " + e.getMessage(), e);
        } finally {
            isRendering = false;
        }
    }
}