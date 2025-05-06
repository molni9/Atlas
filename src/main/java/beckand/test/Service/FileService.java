package beckand.test.Service;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public FileDTO uploadFile(FileUploadRequest request) {
        try {
            MultipartFile file = request.getFile();
            String fileName = file.getOriginalFilename();
            log.info("Uploading file: {}", fileName);

            // Определяем правильный MIME-тип для OBJ файлов
            String contentType = file.getContentType();
            if (fileName != null && fileName.toLowerCase().endsWith(".obj")) {
                contentType = "model/obj";
            }

            // Загружаем файл в MinIO
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(fileName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );

            // Создаем DTO с информацией о файле
            FileDTO dto = new FileDTO();
            dto.setFileName(fileName);
            dto.setFileType(contentType);
            dto.setFileSize(file.getSize());
            dto.setDescription(request.getDescription());
            dto.setS3ObjectKey(fileName);

            log.info("File uploaded successfully: {}, type: {}", fileName, contentType);
            return dto;
        } catch (Exception e) {
            log.error("Failed to upload file", e);
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String objectKey) {
        try {
            log.info("Deleting file: {}", objectKey);
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
            log.info("File deleted successfully: {}", objectKey);
        } catch (Exception e) {
            log.error("Failed to delete file: {}", objectKey, e);
            throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
        }
    }

    public FileDTO getFileInfo(String objectKey) {
        try {
            log.info("Getting file info: {}", objectKey);
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );

            // Устанавливаем правильный MIME-тип для OBJ файлов
            String contentType = stat.contentType();
            if (objectKey.toLowerCase().endsWith(".obj")) {
                contentType = "model/obj";
            }

            FileDTO dto = new FileDTO();
            dto.setFileName(objectKey);
            dto.setFileType(contentType);
            dto.setFileSize(stat.size());
            dto.setS3ObjectKey(objectKey);

            log.info("File info retrieved successfully: {}, type: {}", objectKey, contentType);
            return dto;
        } catch (Exception e) {
            log.error("Failed to get file info: {}", objectKey, e);
            throw new RuntimeException("Failed to get file info: " + e.getMessage(), e);
        }
    }

    public List<FileDTO> getAllFiles() {
        try {
            log.info("Getting all files");
            List<FileDTO> files = new ArrayList<>();
            
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                FileDTO dto = new FileDTO();
                dto.setFileName(item.objectName());
                dto.setFileSize(item.size());
                dto.setS3ObjectKey(item.objectName());
                
                // Получаем дополнительную информацию о файле
                try {
                    StatObjectResponse stat = minioClient.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(item.objectName())
                                    .build()
                    );
                    // Устанавливаем правильный MIME-тип для OBJ файлов
                    String contentType = stat.contentType();
                    if (item.objectName().toLowerCase().endsWith(".obj")) {
                        contentType = "model/obj";
                    }
                    dto.setFileType(contentType);
                } catch (Exception e) {
                    log.warn("Failed to get file type for: {}", item.objectName(), e);
                }
                
                files.add(dto);
            }

            log.info("Retrieved {} files", files.size());
            return files;
        } catch (Exception e) {
            log.error("Failed to list files", e);
            throw new RuntimeException("Failed to list files: " + e.getMessage(), e);
        }
    }

    public InputStream getFileContent(String objectKey) {
        try {
            log.info("Getting file content: {}", objectKey);
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to get file content: {}", objectKey, e);
            throw new RuntimeException("Failed to get file content: " + e.getMessage(), e);
        }
    }
}