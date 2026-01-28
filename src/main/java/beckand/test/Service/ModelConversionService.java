package beckand.test.Service;

import de.javagl.obj.Obj;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для конвертации OBJ моделей в glTF формат
 */
@Slf4j
@Service
public class ModelConversionService {
    
    private final Map<String, String> gltfCache = new ConcurrentHashMap<>();

    /**
     * Конвертирует OBJ файл в glTF JSON строку
     * @param objectKey ключ объекта для кэширования
     * @param objBytes байты OBJ файла
     * @return glTF JSON строка
     */
    public String convertObjToGltf(String objectKey, byte[] objBytes) {
        // Проверяем кэш
        String cacheKey = objectKey + "_gltf";
        if (gltfCache.containsKey(cacheKey)) {
            log.debug("Returning cached glTF for: {}", objectKey);
            return gltfCache.get(cacheKey);
        }
        
        String gltf = convertObjToGltfInternal(objBytes);
        gltfCache.put(cacheKey, gltf);
        return gltf;
    }
    
    /**
     * Внутренний метод конвертации
     */
    private String convertObjToGltfInternal(byte[] objBytes) {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(objBytes), StandardCharsets.UTF_8)) {
            Obj obj = ObjReader.read(reader);
            
            log.info("Converting OBJ to glTF: {} vertices, {} normals, {} faces", 
                obj.getNumVertices(), obj.getNumNormals(), obj.getNumFaces());
            
            // Проверяем наличие нормалей в OBJ
            boolean hasNormals = obj.getNumNormals() > 0;
            log.info("OBJ file has normals: {}", hasNormals);
            
            // Нормализуем вершины
            List<float[]> vertices = normalizeVertices(obj);
            
            // Загружаем нормали из OBJ, если они есть
            List<float[]> objNormals = null;
            if (hasNormals) {
                objNormals = new ArrayList<>();
                for (int i = 0; i < obj.getNumNormals(); i++) {
                    var normal = obj.getNormal(i);
                    objNormals.add(new float[]{
                        (float) normal.getX(),
                        (float) normal.getY(),
                        (float) normal.getZ()
                    });
                }
            }
            
            // Создаем индексы для треугольников
            List<Integer> indices = new ArrayList<>();
            List<float[]> faceNormals = new ArrayList<>();
            
            for (int i = 0; i < obj.getNumFaces(); i++) {
                ObjFace face = obj.getFace(i);
                if (face.getNumVertices() < 3) {
                    continue;
                }
                
                // Триангулируем полигон
                for (int t = 1; t < face.getNumVertices() - 1; t++) {
                    int v0 = face.getVertexIndex(0);
                    int v1 = face.getVertexIndex(t);
                    int v2 = face.getVertexIndex(t + 1);
                    
                    indices.add(v0);
                    indices.add(v1);
                    indices.add(v2);
                    
                    // Используем нормали из OBJ, если они есть, иначе вычисляем
                    float[] normal;
                    if (hasNormals && objNormals != null) {
                        // Пытаемся получить нормали из OBJ для вершин треугольника
                        try {
                            int n0 = face.getNormalIndex(0);
                            int n1 = face.getNormalIndex(t);
                            int n2 = face.getNormalIndex(t + 1);
                            
                            // Проверяем, что индексы нормалей валидны
                            if (n0 >= 0 && n0 < objNormals.size() && 
                                n1 >= 0 && n1 < objNormals.size() && 
                                n2 >= 0 && n2 < objNormals.size()) {
                                // Усредняем нормали трех вершин треугольника
                                float[] n0v = objNormals.get(n0);
                                float[] n1v = objNormals.get(n1);
                                float[] n2v = objNormals.get(n2);
                                
                                normal = new float[]{
                                    (n0v[0] + n1v[0] + n2v[0]) / 3f,
                                    (n0v[1] + n1v[1] + n2v[1]) / 3f,
                                    (n0v[2] + n1v[2] + n2v[2]) / 3f
                                };
                                
                                // Нормализуем
                                float length = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
                                if (length > 0.0001f) {
                                    normal[0] /= length;
                                    normal[1] /= length;
                                    normal[2] /= length;
                                }
                            } else {
                                // Индексы нормалей невалидны, вычисляем нормаль
                                normal = calculateNormal(vertices.get(v0), vertices.get(v1), vertices.get(v2));
                            }
                        } catch (Exception e) {
                            // Если не удалось получить нормали из OBJ, вычисляем
                            log.debug("Could not get normals from OBJ face, computing: {}", e.getMessage());
                            normal = calculateNormal(vertices.get(v0), vertices.get(v1), vertices.get(v2));
                        }
                    } else {
                        // Вычисляем нормаль для треугольника
                        normal = calculateNormal(vertices.get(v0), vertices.get(v1), vertices.get(v2));
                    }
                    faceNormals.add(normal);
                }
            }
            
            // Определяем тип индексов: если больше 65535, используем int (4 байта), иначе short (2 байта)
            boolean useIntIndices = vertices.size() > 65535;
            
            // Создаем glTF JSON с использованием нормалей из OBJ
            return buildGltfJson(vertices, indices, faceNormals, objNormals, useIntIndices);
            
        } catch (Exception e) {
            log.error("Error converting OBJ to glTF", e);
            throw new IllegalStateException("Unable to convert OBJ to glTF: " + e.getMessage(), e);
        }
    }
    
    private List<float[]> normalizeVertices(Obj obj) {
        // Находим границы модели
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        
        List<float[]> originalVertices = new ArrayList<>();
        for (int i = 0; i < obj.getNumVertices(); i++) {
            var vertex = obj.getVertex(i);
            float x = (float) vertex.getX();
            float y = (float) vertex.getY();
            float z = (float) vertex.getZ();
            originalVertices.add(new float[]{x, y, z});
            
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        
        // Вычисляем центр и масштаб
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float centerZ = (minZ + maxZ) / 2f;
        
        float extentX = maxX - minX;
        float extentY = maxY - minY;
        float extentZ = maxZ - minZ;
        float maxExtent = Math.max(Math.max(extentX, extentY), extentZ);
        float scale = maxExtent == 0 ? 1.0f : 2.0f / maxExtent;
        
        // Нормализуем вершины
        List<float[]> normalized = new ArrayList<>();
        for (float[] vertex : originalVertices) {
            normalized.add(new float[]{
                (vertex[0] - centerX) * scale,
                (vertex[1] - centerY) * scale,
                (vertex[2] - centerZ) * scale
            });
        }
        
        return normalized;
    }
    
    private float[] calculateNormal(float[] v0, float[] v1, float[] v2) {
        // Вычисляем векторы двух сторон треугольника
        float[] edge1 = {v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]};
        float[] edge2 = {v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]};
        
        // Вычисляем векторное произведение (нормаль)
        float[] normal = {
            edge1[1] * edge2[2] - edge1[2] * edge2[1],
            edge1[2] * edge2[0] - edge1[0] * edge2[2],
            edge1[0] * edge2[1] - edge1[1] * edge2[0]
        };
        
        // Нормализуем
        float length = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        if (length > 0.0001f) {
            normal[0] /= length;
            normal[1] /= length;
            normal[2] /= length;
        }
        
        return normal;
    }
    
    private String buildGltfJson(List<float[]> vertices, List<Integer> indices, List<float[]> faceNormals, List<float[]> objNormals, boolean useIntIndices) {
        // Создаем байтовые буферы для вершин и индексов
        List<Float> vertexBuffer = new ArrayList<>();
        List<Float> normalBuffer = new ArrayList<>();
        
        for (float[] vertex : vertices) {
            vertexBuffer.add(vertex[0]);
            vertexBuffer.add(vertex[1]);
            vertexBuffer.add(vertex[2]);
        }
        
        // Если есть нормали из OBJ, используем их напрямую через индексы
        if (objNormals != null && !objNormals.isEmpty()) {
            // Создаем карту нормалей по индексам вершин
            Map<Integer, List<float[]>> vertexNormals = new HashMap<>();
            for (int i = 0; i < indices.size(); i += 3) {
                int idx = i / 3;
                if (idx < faceNormals.size()) {
                    float[] normal = faceNormals.get(idx);
                    vertexNormals.computeIfAbsent(indices.get(i), k -> new ArrayList<>()).add(normal);
                    vertexNormals.computeIfAbsent(indices.get(i + 1), k -> new ArrayList<>()).add(normal);
                    vertexNormals.computeIfAbsent(indices.get(i + 2), k -> new ArrayList<>()).add(normal);
                }
            }
            
            // Вычисляем средние нормали для вершин
            for (int i = 0; i < vertices.size(); i++) {
                List<float[]> norms = vertexNormals.getOrDefault(i, new ArrayList<>());
                if (norms.isEmpty()) {
                    normalBuffer.add(0f);
                    normalBuffer.add(1f);
                    normalBuffer.add(0f);
                } else {
                    float[] avgNormal = {0f, 0f, 0f};
                    for (float[] norm : norms) {
                        avgNormal[0] += norm[0];
                        avgNormal[1] += norm[1];
                        avgNormal[2] += norm[2];
                    }
                    float count = norms.size();
                    avgNormal[0] /= count;
                    avgNormal[1] /= count;
                    avgNormal[2] /= count;
                    
                    // Нормализуем
                    float length = (float) Math.sqrt(avgNormal[0] * avgNormal[0] + avgNormal[1] * avgNormal[1] + avgNormal[2] * avgNormal[2]);
                    if (length > 0.0001f) {
                        avgNormal[0] /= length;
                        avgNormal[1] /= length;
                        avgNormal[2] /= length;
                    }
                    
                    normalBuffer.add(avgNormal[0]);
                    normalBuffer.add(avgNormal[1]);
                    normalBuffer.add(avgNormal[2]);
                }
            }
        } else {
            // Вычисляем нормали из треугольников
            Map<Integer, List<float[]>> vertexNormals = new HashMap<>();
            for (int i = 0; i < indices.size(); i += 3) {
                int idx = i / 3;
                if (idx < faceNormals.size()) {
                    float[] normal = faceNormals.get(idx);
                    vertexNormals.computeIfAbsent(indices.get(i), k -> new ArrayList<>()).add(normal);
                    vertexNormals.computeIfAbsent(indices.get(i + 1), k -> new ArrayList<>()).add(normal);
                    vertexNormals.computeIfAbsent(indices.get(i + 2), k -> new ArrayList<>()).add(normal);
                }
            }
            
            // Вычисляем средние нормали для вершин
            for (int i = 0; i < vertices.size(); i++) {
                List<float[]> norms = vertexNormals.getOrDefault(i, new ArrayList<>());
                if (norms.isEmpty()) {
                    normalBuffer.add(0f);
                    normalBuffer.add(1f);
                    normalBuffer.add(0f);
                } else {
                    float[] avgNormal = {0f, 0f, 0f};
                    for (float[] norm : norms) {
                        avgNormal[0] += norm[0];
                        avgNormal[1] += norm[1];
                        avgNormal[2] += norm[2];
                    }
                    float count = norms.size();
                    avgNormal[0] /= count;
                    avgNormal[1] /= count;
                    avgNormal[2] /= count;
                    
                    // Нормализуем
                    float length = (float) Math.sqrt(avgNormal[0] * avgNormal[0] + avgNormal[1] * avgNormal[1] + avgNormal[2] * avgNormal[2]);
                    if (length > 0.0001f) {
                        avgNormal[0] /= length;
                        avgNormal[1] /= length;
                        avgNormal[2] /= length;
                    }
                    
                    normalBuffer.add(avgNormal[0]);
                    normalBuffer.add(avgNormal[1]);
                    normalBuffer.add(avgNormal[2]);
                }
            }
        }
        
        // Кодируем данные в base64
        String vertexBase64 = encodeFloatArrayToBase64(vertexBuffer);
        String normalBase64 = encodeFloatArrayToBase64(normalBuffer);
        String indexBase64 = encodeIntArrayToBase64(indices, useIntIndices);
        
        // Вычисляем размеры буферов
        int vertexBufferSize = vertexBuffer.size() * 4; // float = 4 bytes
        int normalBufferSize = normalBuffer.size() * 4;
        int indexBufferSize = indices.size() * (useIntIndices ? 4 : 2);
        
        // Создаем glTF JSON
        StringBuilder gltf = new StringBuilder();
        gltf.append("{\n");
        gltf.append("  \"asset\": {\n");
        gltf.append("    \"version\": \"2.0\",\n");
        gltf.append("    \"generator\": \"OBJ to glTF Converter\"\n");
        gltf.append("  },\n");
        gltf.append("  \"scene\": 0,\n");
        gltf.append("  \"scenes\": [{\n");
        gltf.append("    \"nodes\": [0]\n");
        gltf.append("  }],\n");
        gltf.append("  \"nodes\": [{\n");
        gltf.append("    \"mesh\": 0\n");
        gltf.append("  }],\n");
        gltf.append("  \"meshes\": [{\n");
        gltf.append("    \"primitives\": [{\n");
        gltf.append("      \"attributes\": {\n");
        gltf.append("        \"POSITION\": 0,\n");
        gltf.append("        \"NORMAL\": 1\n");
        gltf.append("      },\n");
        gltf.append("      \"indices\": 2\n");
        gltf.append("    }]\n");
        gltf.append("  }],\n");
        gltf.append("  \"accessors\": [\n");
        gltf.append("    {\n");
        gltf.append("      \"bufferView\": 0,\n");
        gltf.append("      \"componentType\": 5126,\n");
        gltf.append("      \"count\": ").append(vertices.size()).append(",\n");
        gltf.append("      \"type\": \"VEC3\",\n");
        gltf.append("      \"max\": [1.0, 1.0, 1.0],\n");
        gltf.append("      \"min\": [-1.0, -1.0, -1.0]\n");
        gltf.append("    },\n");
        gltf.append("    {\n");
        gltf.append("      \"bufferView\": 1,\n");
        gltf.append("      \"componentType\": 5126,\n");
        gltf.append("      \"count\": ").append(vertices.size()).append(",\n");
        gltf.append("      \"type\": \"VEC3\"\n");
        gltf.append("    },\n");
        gltf.append("    {\n");
        gltf.append("      \"bufferView\": 2,\n");
        gltf.append("      \"componentType\": ").append(useIntIndices ? 5125 : 5123).append(",\n"); // 5125 = UNSIGNED_INT, 5123 = UNSIGNED_SHORT
        gltf.append("      \"count\": ").append(indices.size()).append(",\n");
        gltf.append("      \"type\": \"SCALAR\"\n");
        gltf.append("    }\n");
        gltf.append("  ],\n");
        gltf.append("  \"bufferViews\": [\n");
        gltf.append("    {\n");
        gltf.append("      \"buffer\": 0,\n");
        gltf.append("      \"byteOffset\": 0,\n");
        gltf.append("      \"byteLength\": ").append(vertexBufferSize).append("\n");
        gltf.append("    },\n");
        gltf.append("    {\n");
        gltf.append("      \"buffer\": 0,\n");
        gltf.append("      \"byteOffset\": ").append(vertexBufferSize).append(",\n");
        gltf.append("      \"byteLength\": ").append(normalBufferSize).append("\n");
        gltf.append("    },\n");
        gltf.append("    {\n");
        gltf.append("      \"buffer\": 0,\n");
        gltf.append("      \"byteOffset\": ").append(vertexBufferSize + normalBufferSize).append(",\n");
        gltf.append("      \"byteLength\": ").append(indexBufferSize).append("\n");
        gltf.append("    }\n");
        gltf.append("  ],\n");
        gltf.append("  \"buffers\": [{\n");
        gltf.append("    \"uri\": \"data:application/octet-stream;base64,");
        gltf.append(encodeBuffersToBase64(vertexBuffer, normalBuffer, indices, useIntIndices));
        gltf.append("\",\n");
        gltf.append("    \"byteLength\": ").append(vertexBufferSize + normalBufferSize + indexBufferSize).append("\n");
        gltf.append("  }]\n");
        gltf.append("}");
        
        return gltf.toString();
    }
    
    private String encodeFloatArrayToBase64(List<Float> floats) {
        byte[] bytes = new byte[floats.size() * 4];
        int idx = 0;
        for (Float f : floats) {
            int bits = Float.floatToIntBits(f);
            bytes[idx++] = (byte) (bits & 0xFF);
            bytes[idx++] = (byte) ((bits >> 8) & 0xFF);
            bytes[idx++] = (byte) ((bits >> 16) & 0xFF);
            bytes[idx++] = (byte) ((bits >> 24) & 0xFF);
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    
    private String encodeIntArrayToBase64(List<Integer> ints, boolean useInt) {
        byte[] bytes = new byte[ints.size() * (useInt ? 4 : 2)];
        int idx = 0;
        for (Integer i : ints) {
            if (useInt) {
                // Используем int (4 байта)
                bytes[idx++] = (byte) (i & 0xFF);
                bytes[idx++] = (byte) ((i >> 8) & 0xFF);
                bytes[idx++] = (byte) ((i >> 16) & 0xFF);
                bytes[idx++] = (byte) ((i >> 24) & 0xFF);
            } else {
                // Используем short (2 байта)
                short s = i.shortValue();
                bytes[idx++] = (byte) (s & 0xFF);
                bytes[idx++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    
    private String encodeBuffersToBase64(List<Float> vertices, List<Float> normals, List<Integer> indices, boolean useIntIndices) {
        int totalSize = vertices.size() * 4 + normals.size() * 4 + indices.size() * (useIntIndices ? 4 : 2);
        byte[] buffer = new byte[totalSize];
        int offset = 0;
        
        // Вершины
        for (Float f : vertices) {
            int bits = Float.floatToIntBits(f);
            buffer[offset++] = (byte) (bits & 0xFF);
            buffer[offset++] = (byte) ((bits >> 8) & 0xFF);
            buffer[offset++] = (byte) ((bits >> 16) & 0xFF);
            buffer[offset++] = (byte) ((bits >> 24) & 0xFF);
        }
        
        // Нормали
        for (Float f : normals) {
            int bits = Float.floatToIntBits(f);
            buffer[offset++] = (byte) (bits & 0xFF);
            buffer[offset++] = (byte) ((bits >> 8) & 0xFF);
            buffer[offset++] = (byte) ((bits >> 16) & 0xFF);
            buffer[offset++] = (byte) ((bits >> 24) & 0xFF);
        }
        
        // Индексы
        for (Integer i : indices) {
            if (useIntIndices) {
                // Используем int (4 байта)
                buffer[offset++] = (byte) (i & 0xFF);
                buffer[offset++] = (byte) ((i >> 8) & 0xFF);
                buffer[offset++] = (byte) ((i >> 16) & 0xFF);
                buffer[offset++] = (byte) ((i >> 24) & 0xFF);
            } else {
                // Используем short (2 байта)
                short s = i.shortValue();
                buffer[offset++] = (byte) (s & 0xFF);
                buffer[offset++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        
        return java.util.Base64.getEncoder().encodeToString(buffer);
    }
}
