package beckand.test.Service;

import com.jogamp.opengl.*;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjFace;
import de.javagl.obj.FloatTuple;
import io.minio.MinioClient;

import javax.imageio.ImageIO;
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
    private static final int WIDTH = 1024;
    private static final int HEIGHT = 768;
    private static final int FPS = 60;
    private static final float MIN_ANGLE_CHANGE = 0.1f;  // Уменьшаем для более плавного вращения
    private static final int MAX_RENDER_SIZE = 2048;  // Максимальный размер рендера
    private static final int MAX_CACHE_SIZE = 50;     // Уменьшаем размер кэша
    private static final long MAX_RENDER_TIME = 500;  // Максимальное время рендеринга в мс

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
    private volatile boolean needsRender = true;
    private String currentModelId = null;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    private float centerX = 0, centerY = 0, centerZ = 0;

    // Добавляем кэш для рендеров
    private final Map<String, byte[]> renderCache = new ConcurrentHashMap<>();

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public RenderService() {
        try {
            System.setProperty("java.awt.headless", "true");
            GLProfile.initSingleton();

        GLProfile profile = GLProfile.get(GLProfile.GL2);
            if (profile == null) {
                throw new RuntimeException("GL2 profile is not available");
            }

        GLCapabilities capabilities = new GLCapabilities(profile);
            capabilities.setHardwareAccelerated(true);
            capabilities.setDoubleBuffered(true);
            capabilities.setDepthBits(24);
            capabilities.setSampleBuffers(true);
            capabilities.setNumSamples(4);

            GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            drawable = factory.createOffscreenAutoDrawable(
                    null,
                    capabilities,
                    null,
                    WIDTH,
                    HEIGHT
            );

            // Предварительно выделяем буфер для пикселей
            pixelBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            pixelBuffer.order(ByteOrder.nativeOrder());

            drawable.addGLEventListener(new GLEventListener() {
            @Override
            public void init(GLAutoDrawable drawable) {
                    GL gl = drawable.getGL();
                    if (!(gl instanceof GL2)) {
                        throw new RuntimeException("GL2 is not available");
                    }
                    GL2 gl2 = gl.getGL2();

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
                }

            @Override
                public void dispose(GLAutoDrawable drawable) {
                    isInitialized = false;
                }

            @Override
            public void display(GLAutoDrawable drawable) {
                if (!isInitialized) return;

                GL gl = drawable.getGL();
                if (!(gl instanceof GL2)) {
                    return;
                }
                GL2 gl2 = gl.getGL2();

                // Всегда обновляем рендер при изменении углов
                if (Math.abs(currentAzimuth - targetAzimuth) > 0.01f || 
                    Math.abs(currentElevation - targetElevation) > 0.01f) {
                    needsRender = true;
                }

                if (!needsRender) {
                    return;
                }

                gl2.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

                // Плавно обновляем углы
                float delta = 0.2f; // Увеличиваем скорость обновления
                if (Math.abs(currentAzimuth - targetAzimuth) > 0.01f) {
                    currentAzimuth += (targetAzimuth - currentAzimuth) * delta;
                } else {
                    currentAzimuth = targetAzimuth;
                }
                if (Math.abs(currentElevation - targetElevation) > 0.01f) {
                    currentElevation += (targetElevation - currentElevation) * delta;
                } else {
                    currentElevation = targetElevation;
                }

                // Нормализуем углы
                while (currentAzimuth > 360) currentAzimuth -= 360;
                while (currentAzimuth < 0) currentAzimuth += 360;
                currentElevation = Math.max(-80, Math.min(80, currentElevation));

                // Настройка проекции и камеры
                gl2.glMatrixMode(GL2.GL_PROJECTION);
                gl2.glLoadIdentity();
                glu.gluPerspective(45.0, (double) WIDTH / HEIGHT, 0.1, 100.0);

                gl2.glMatrixMode(GL2.GL_MODELVIEW);
                gl2.glLoadIdentity();

                // Вычисляем позицию камеры
                double radius = 20.0;
                double az = Math.toRadians(currentAzimuth);
                double el = Math.toRadians(currentElevation);
                double x = radius * Math.cos(el) * Math.sin(az);
                double y = radius * Math.sin(el);
                double z = radius * Math.cos(el) * Math.cos(az);

                glu.gluLookAt(x, y, z, centerX, centerY, centerZ, 0, 1, 0);

                // Отрисовка модели
                if (currentModel != null) {
                    gl2.glPushMatrix();
                    gl2.glTranslatef(-centerX, -centerY, -centerZ);

                    // Настройка освещения
                    float[] lightPosition = {1.0f, 1.0f, 1.0f, 0.0f};
                    float[] lightAmbient = {0.2f, 0.2f, 0.2f, 1.0f};
                    float[] lightDiffuse = {0.8f, 0.8f, 0.8f, 1.0f};
                    float[] lightSpecular = {1.0f, 1.0f, 1.0f, 1.0f};
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, lightPosition, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_AMBIENT, lightAmbient, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, lightDiffuse, 0);
                    gl2.glLightfv(GL2.GL_LIGHT0, GL2.GL_SPECULAR, lightSpecular, 0);

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
                    needsRender = false;
                } catch (Exception e) {
                    log.error("Error reading pixels", e);
                }

                synchronized (renderLock) {
                    renderComplete = true;
                    renderLock.notifyAll();
                }
            }

                @Override
                public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
                    GL gl = drawable.getGL();
                    if (!(gl instanceof GL2)) {
                        return;
                    }
                    GL2 gl2 = gl.getGL2();
                    gl2.glViewport(0, 0, width, height);
                }
            });

            glu = new GLU();
            animator = new FPSAnimator(drawable, FPS);
            animator.start();

            // Ждем инициализации OpenGL контекста
            long startTime = System.currentTimeMillis();
            while (!isInitialized && System.currentTimeMillis() - startTime < 5000) {
                Thread.sleep(100);
            }

            if (!isInitialized) {
                throw new RuntimeException("Failed to initialize OpenGL context");
            }
        } catch (Exception e) {
            log.error("Failed to initialize OpenGL", e);
            throw new RuntimeException("Failed to initialize OpenGL: " + e.getMessage(), e);
                    }
                }

    private void updateModelBounds() {
        if (currentModel == null) return;
        
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        minZ = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        maxZ = -Float.MAX_VALUE;

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

        float maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        log.info("Обновлены границы модели: minX={}, maxX={}, minY={}, maxY={}, minZ={}, maxZ={}, maxDim={}", 
                minX, maxX, minY, maxY, minZ, maxZ, maxDim);
    }

    public byte[] renderModel(String objectKey, InputStream modelStream, String fileType, double azimuth, double elevation) throws IOException {
        if (!isInitialized) {
            throw new IOException("OpenGL context is not initialized");
        }

        try {
            // Загружаем модель только если она изменилась
            if (!objectKey.equals(currentModelId)) {
                currentModel = ObjReader.read(modelStream);
                if (currentModel.getNumFaces() == 0 || currentModel.getNumVertices() == 0) {
                    throw new IOException("Модель не содержит вершин или граней");
                }
                currentModelId = objectKey;
                updateModelBounds();
            }

            // Обновляем углы
            currentAzimuth = (float) azimuth;
            currentElevation = (float) elevation;
            needsRender = true;

            // Ждем завершения рендеринга
            synchronized (renderLock) {
                renderComplete = false;
                while (!renderComplete) {
                    try {
                        renderLock.wait(10);  // Уменьшаем время ожидания
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Rendering was interrupted", e);
                    }
                }
            }

            // Создаем изображение из буфера
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
        }
    }

    private boolean isValidAngles(double azimuth, double elevation) {
        return !Double.isNaN(azimuth) && !Double.isNaN(elevation) &&
               !Double.isInfinite(azimuth) && !Double.isInfinite(elevation) &&
               elevation >= -80 && elevation <= 80 &&
               azimuth >= 0 && azimuth <= 360;
    }

    public byte[] getModelData(String objectKey) {
        try {
            return minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build()
            ).readAllBytes();
        } catch (Exception e) {
            log.error("Error getting model data from MinIO", e);
            return null;
        }
    }

    // Добавляем метод очистки кэша
    public void clearRenderCache() {
        renderCache.clear();
    }

    // Добавляем метод для очистки всех кэшей
    public void clearAllCaches() {
        modelCache.clear();
        renderCache.clear();
        log.info("All caches cleared");
    }
}