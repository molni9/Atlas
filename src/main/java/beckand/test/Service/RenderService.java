package beckand.test.Service;

import de.javagl.obj.Obj;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RenderService {

    private static final Color BACKGROUND_COLOR = new Color(12, 16, 24);
    private static final Color BASE_COLOR = new Color(180, 195, 210);
    private static final Color EDGE_COLOR = new Color(25, 35, 55, 90);
    private static final Vector3 LIGHT_DIRECTION = new Vector3(0.25, 0.85, 0.45).normalize();

    @Value("${render.width:1280}")
    private int renderWidth;

    @Value("${render.height:720}")
    private int renderHeight;

    @Value("${render.jpeg.quality:0.9}")
    private float jpegQuality;

    @Value("${render.angle.step.deg:1}")
    private double angleSnapDeg;

    @Value("${render.cache.max.entries:200}")
    private int maxCacheEntries;

    private final Map<String, FrameCacheEntry> frameCache = new ConcurrentHashMap<>();
    private final Map<String, MeshCacheEntry> meshCache = new ConcurrentHashMap<>();

    public byte[] renderModel(String objectKey,
                              byte[] modelBytes,
                              String contentType,
                              double azimuth,
                              double elevation) {
        double snappedAzimuth = snapAngle(azimuth);
        double snappedElevation = snapAngle(elevation);
        String cacheKey = buildFrameKey(objectKey, snappedAzimuth, snappedElevation);

        FrameCacheEntry cached = frameCache.get(cacheKey);
        if (cached != null) {
            cached.touch();
            return cached.payload();
        }

        RenderMesh mesh = resolveMesh(objectKey, modelBytes);
        byte[] jpeg = render(mesh, snappedAzimuth, snappedElevation);
        rememberFrame(cacheKey, jpeg);
        log.debug("Rendered model {} (az={}, el={}, type={})", objectKey, snappedAzimuth, snappedElevation, contentType);
        return jpeg;
    }

    private RenderMesh resolveMesh(String objectKey, byte[] modelBytes) {
        byte[] hash = sha256(modelBytes);
        MeshCacheEntry cached = meshCache.get(objectKey);
        if (cached != null && Arrays.equals(hash, cached.sha256())) {
            cached.touch();
            return cached.mesh();
        }

        RenderMesh mesh = buildMesh(modelBytes);
        meshCache.put(objectKey, new MeshCacheEntry(hash, mesh));
        trimCache(meshCache, Math.max(1, maxCacheEntries / 4));
        return mesh;
    }

    private RenderMesh buildMesh(byte[] modelBytes) {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(modelBytes), StandardCharsets.UTF_8)) {
            Obj obj = ObjUtils.convertToRenderable(ObjReader.read(reader));
            List<Vector3> vertices = new ArrayList<>(obj.getNumVertices());
            for (int i = 0; i < obj.getNumVertices(); i++) {
                var vertex = obj.getVertex(i);
                vertices.add(new Vector3(vertex.getX(), vertex.getY(), vertex.getZ()));
            }

            List<Triangle> triangles = new ArrayList<>(obj.getNumFaces());
            for (int i = 0; i < obj.getNumFaces(); i++) {
                ObjFace face = obj.getFace(i);
                if (face.getNumVertices() < 3) {
                    continue;
                }
                for (int t = 1; t < face.getNumVertices() - 1; t++) {
                    triangles.add(new Triangle(
                            face.getVertexIndex(0),
                            face.getVertexIndex(t),
                            face.getVertexIndex(t + 1)
                    ));
                }
            }

            List<Vector3> normalized = normalize(vertices);
            return new RenderMesh(normalized, triangles);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse OBJ model", e);
        }
    }

    private List<Vector3> normalize(List<Vector3> vertices) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (Vector3 vertex : vertices) {
            minX = Math.min(minX, vertex.x());
            minY = Math.min(minY, vertex.y());
            minZ = Math.min(minZ, vertex.z());
            maxX = Math.max(maxX, vertex.x());
            maxY = Math.max(maxY, vertex.y());
            maxZ = Math.max(maxZ, vertex.z());
        }

        Vector3 center = new Vector3(
                (minX + maxX) / 2d,
                (minY + maxY) / 2d,
                (minZ + maxZ) / 2d
        );
        double extent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
        double scale = extent == 0 ? 1.0 : 2.0 / extent;

        List<Vector3> normalized = new ArrayList<>(vertices.size());
        for (Vector3 vertex : vertices) {
            normalized.add(vertex.subtract(center).scale(scale));
        }
        return normalized;
    }

    private byte[] render(RenderMesh mesh, double azimuthDeg, double elevationDeg) {
        BufferedImage image = new BufferedImage(renderWidth, renderHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setColor(BACKGROUND_COLOR);
        g2d.fillRect(0, 0, renderWidth, renderHeight);

        double scale = Math.min(renderWidth, renderHeight) * 0.42;
        List<DrawableTriangle> toDraw = new ArrayList<>(mesh.triangles().size());
        for (Triangle triangle : mesh.triangles()) {
            Vector3 v0 = rotate(mesh.vertices().get(triangle.a()), azimuthDeg, elevationDeg);
            Vector3 v1 = rotate(mesh.vertices().get(triangle.b()), azimuthDeg, elevationDeg);
            Vector3 v2 = rotate(mesh.vertices().get(triangle.c()), azimuthDeg, elevationDeg);

            Vector3 normal = v1.subtract(v0).cross(v2.subtract(v0)).normalize();
            double depth = (v0.z() + v1.z() + v2.z()) / 3d;
            double shade = Math.max(0.2, normal.dot(LIGHT_DIRECTION));

            Path2D path = new Path2D.Double();
            Point2 p0 = project(v0, scale);
            Point2 p1 = project(v1, scale);
            Point2 p2 = project(v2, scale);
            path.moveTo(p0.x(), p0.y());
            path.lineTo(p1.x(), p1.y());
            path.lineTo(p2.x(), p2.y());
            path.closePath();

            toDraw.add(new DrawableTriangle(depth, path, shade(BASE_COLOR, shade)));
        }

        toDraw.stream()
                .sorted(Comparator.comparingDouble(DrawableTriangle::depth))
                .forEach(triangle -> {
                    g2d.setColor(triangle.color());
                    g2d.fill(triangle.path());
                    g2d.setColor(EDGE_COLOR);
                    g2d.setStroke(new BasicStroke(0.6f));
                    g2d.draw(triangle.path());
                });
        g2d.dispose();
        return encode(image);
    }

    private Vector3 rotate(Vector3 vertex, double azimuthDeg, double elevationDeg) {
        double az = Math.toRadians(azimuthDeg);
        double el = Math.toRadians(elevationDeg);

        double cosAz = Math.cos(az);
        double sinAz = Math.sin(az);
        double x1 = vertex.x() * cosAz - vertex.z() * sinAz;
        double z1 = vertex.x() * sinAz + vertex.z() * cosAz;

        double cosEl = Math.cos(el);
        double sinEl = Math.sin(el);
        double y2 = vertex.y() * cosEl - z1 * sinEl;
        double z2 = vertex.y() * sinEl + z1 * cosEl;

        return new Vector3(x1, y2, z2);
    }

    private Point2 project(Vector3 vertex, double scale) {
        double distance = 3.0;
        double perspective = distance / (distance - vertex.z());
        double x = (renderWidth / 2.0) + vertex.x() * scale * perspective;
        double y = (renderHeight / 2.0) - vertex.y() * scale * perspective;
        return new Point2(x, y);
    }

    private byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(baos)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.min(1f, Math.max(0.1f, jpegQuality)));
            }
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
            writer.dispose();
            imageOutput.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode JPEG", e);
        }
    }

    private void rememberFrame(String cacheKey, byte[] payload) {
        frameCache.put(cacheKey, new FrameCacheEntry(payload));
        trimCache(frameCache, maxCacheEntries);
    }

    private <T extends Comparable<T>> void trimCache(Map<String, T> cache, int limit) {
        if (limit <= 0 || cache.size() <= limit) {
            return;
        }
        int toRemove = cache.size() - limit;
        List<Map.Entry<String, T>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            cache.remove(entries.get(i).getKey());
        }
    }

    private double snapAngle(double angle) {
        double step = Math.max(0.1, angleSnapDeg);
        return Math.round(angle / step) * step;
    }

    private String buildFrameKey(String objectKey, double azimuth, double elevation) {
        return objectKey + ':' + azimuth + ':' + elevation;
    }

    private byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute hash", e);
        }
    }

    @PreDestroy
    public void clearCaches() {
        frameCache.clear();
        meshCache.clear();
    }

    private static Color shade(Color base, double light) {
        double factor = Math.min(1.0, Math.max(0.2, light));
        int r = clamp((int) (base.getRed() * factor));
        int g = clamp((int) (base.getGreen() * factor));
        int b = clamp((int) (base.getBlue() * factor));
        return new Color(r, g, b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record RenderMesh(List<Vector3> vertices, List<Triangle> triangles) {
    }

    private record Triangle(int a, int b, int c) {
    }

    private record Point2(double x, double y) {
    }

    private record DrawableTriangle(double depth, Path2D path, Color color) {
    }

    private static final class FrameCacheEntry implements Comparable<FrameCacheEntry> {
        private final byte[] payload;
        private final long createdAtNanos = System.nanoTime();
        private volatile long lastAccessNanos = createdAtNanos;

        FrameCacheEntry(byte[] payload) {
            this.payload = payload;
        }

        byte[] payload() {
            return payload;
        }

        void touch() {
            lastAccessNanos = System.nanoTime();
        }

        @Override
        public int compareTo(FrameCacheEntry other) {
            return Long.compare(this.lastAccessNanos, other.lastAccessNanos);
        }
    }

    private static final class MeshCacheEntry implements Comparable<MeshCacheEntry> {
        private final byte[] sha256;
        private final RenderMesh mesh;
        private volatile long lastAccessNanos = System.nanoTime();

        MeshCacheEntry(byte[] sha256, RenderMesh mesh) {
            this.sha256 = sha256;
            this.mesh = mesh;
        }

        byte[] sha256() {
            return sha256;
        }

        RenderMesh mesh() {
            return mesh;
        }

        void touch() {
            lastAccessNanos = System.nanoTime();
        }

        @Override
        public int compareTo(MeshCacheEntry other) {
            return Long.compare(this.lastAccessNanos, other.lastAccessNanos);
        }
    }

    private record Vector3(double x, double y, double z) {
        Vector3 subtract(Vector3 other) {
            return new Vector3(x - other.x, y - other.y, z - other.z);
        }

        Vector3 scale(double factor) {
            return new Vector3(x * factor, y * factor, z * factor);
        }

        Vector3 cross(Vector3 other) {
            return new Vector3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x
            );
        }

        Vector3 normalize() {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length == 0) {
                return this;
            }
            return new Vector3(x / length, y / length, z / length);
        }

        double dot(Vector3 other) {
            return x * other.x + y * other.y + z * other.z;
        }
    }
}

