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

@Slf4j
@Service
public class RenderService {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    // Храним модель для доступа в GLEventListener
    private Obj currentObj = null;

    public byte[] renderModel(InputStream modelStream, String fileType, double azimuth, double elevation) throws IOException {
        // Пробуем загрузить OBJ
        try {
            currentObj = ObjReader.read(modelStream);
            log.info("OBJ faces: " + currentObj.getNumFaces());
            log.info("OBJ vertices: " + currentObj.getNumVertices());
        } catch (Exception e) {
            log.error("Не удалось загрузить OBJ: ", e);
            throw new IOException("Ошибка чтения OBJ", e);
        }

        GLProfile profile = GLProfile.get(GLProfile.GL2);
        GLCapabilities capabilities = new GLCapabilities(profile);
        GLCanvas canvas = new GLCanvas(capabilities);
        canvas.setSize(WIDTH, HEIGHT);
        Frame frame = new Frame("3D Model Renderer");
        frame.add(canvas);
        frame.pack();
        frame.setVisible(true);
        FPSAnimator animator = new FPSAnimator(canvas, 60);
        animator.start();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        canvas.addGLEventListener(new GLEventListener() {
            @Override
            public void init(GLAutoDrawable drawable) {
                GL2 gl = drawable.getGL().getGL2();
                gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                gl.glEnable(GL2.GL_DEPTH_TEST);
                gl.glEnable(GL2.GL_LIGHTING);
                gl.glEnable(GL2.GL_LIGHT0);
                gl.glEnable(GL2.GL_COLOR_MATERIAL);
            }
            @Override
            public void dispose(GLAutoDrawable drawable) {}
            @Override
            public void display(GLAutoDrawable drawable) {
                GL2 gl = drawable.getGL().getGL2();
                gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
                gl.glMatrixMode(GL2.GL_PROJECTION);
                gl.glLoadIdentity();
                GLU glu = new GLU();
                glu.gluPerspective(45.0, (double) WIDTH / HEIGHT, 0.1, 100.0);
                gl.glMatrixMode(GL2.GL_MODELVIEW);
                gl.glLoadIdentity();
                double radius = 5.0;
                double az = Math.toRadians(azimuth);
                double el = Math.toRadians(elevation);
                double x = radius * Math.cos(el) * Math.sin(az);
                double y = radius * Math.sin(el);
                double z = radius * Math.cos(el) * Math.cos(az);
                glu.gluLookAt(x, y, z, 0, 0, 0, 0, 1, 0);
                // Центрирование и масштабирование OBJ
                if (currentObj != null && currentObj.getNumVertices() > 0) {
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                    for (int i = 0; i < currentObj.getNumVertices(); i++) {
                        FloatTuple v = currentObj.getVertex(i);
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
                    gl.glScalef(scale, scale, scale);
                    gl.glTranslatef(-centerX, -centerY, -centerZ);
                }
                // Рендерим OBJ
                if (currentObj != null) {
                    gl.glColor3f(0.7f, 0.7f, 0.7f);
                    gl.glBegin(GL2.GL_TRIANGLES);
                    for (int i = 0; i < currentObj.getNumFaces(); i++) {
                        ObjFace face = currentObj.getFace(i);
                        if (face.getNumVertices() == 3) {
                            for (int j = 0; j < 3; j++) {
                                int idx = face.getVertexIndex(j);
                                FloatTuple vertex = currentObj.getVertex(idx);
                                gl.glVertex3f(vertex.getX(), vertex.getY(), vertex.getZ());
                            }
                        }
                    }
                    gl.glEnd();
                }
                // Тестовый треугольник (должен быть виден всегда)
                gl.glColor3f(1, 0, 0);
                gl.glBegin(GL2.GL_TRIANGLES);
                gl.glVertex3f(-1, -1, 0);
                gl.glVertex3f(1, -1, 0);
                gl.glVertex3f(0, 1, 0);
                gl.glEnd();
                gl.glReadBuffer(GL.GL_FRONT);
                gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buffer);
                buffer.rewind();
                int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < HEIGHT; i++) {
                    for (int j = 0; j < WIDTH; j++) {
                        int index = (HEIGHT - 1 - i) * WIDTH + j;
                        int r = buffer.get(index * 4) & 0xFF;
                        int g = buffer.get(index * 4 + 1) & 0xFF;
                        int b = buffer.get(index * 4 + 2) & 0xFF;
                        pixels[i * WIDTH + j] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            @Override
            public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
                GL2 gl = drawable.getGL().getGL2();
                gl.glViewport(0, 0, width, height);
            }
        });
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ImageIO.write(image, "PNG", outputStream);
        animator.stop();
        frame.dispose();
        return outputStream.toByteArray();
    }
} 