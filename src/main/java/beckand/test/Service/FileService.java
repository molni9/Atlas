package beckand.test.Service;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import beckand.test.Model.FileAttributes;
import beckand.test.Repository.FileAttributesRepository;
import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final MinioClient minioClient;
    private final FileAttributesRepository fileAttributesRepository;

    @Value("${minio.bucket}")
    private String bucket;

    public FileDTO uploadFile(FileUploadRequest request) {
        try {
            MultipartFile file = request.getFile();
            String fileName = file.getOriginalFilename();

            // Определяем правильный MIME-тип для OBJ файлов
            String contentType = file.getContentType();
            if (fileName != null && fileName.toLowerCase().endsWith(".obj")) {
                contentType = "model/obj";
            }
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(fileName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );

            FileDTO dto = new FileDTO();
            dto.setFileType(contentType);
            dto.setS3ObjectKey(fileName);
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                    .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
            fileAttributesRepository.findByS3ObjectKey(objectKey).ifPresent(fileAttributesRepository::delete);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
        }
    }

    public FileDTO getFileInfo(String objectKey) {
        try {
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
            dto.setFileType(contentType);
            dto.setS3ObjectKey(objectKey);
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file info: " + e.getMessage(), e);
        }
    }

    public List<FileDTO> getAllFiles() {
        try {
            List<FileDTO> files = new ArrayList<>();
            
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                FileDTO dto = new FileDTO();
                dto.setS3ObjectKey(item.objectName());
                try {
                    StatObjectResponse stat = minioClient.statObject(
                            StatObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(item.objectName())
                                    .build()
                    );
                    String contentType = stat.contentType();
                    if (item.objectName().toLowerCase().endsWith(".obj")) {
                        contentType = "model/obj";
                    }
                    dto.setFileType(contentType);
                } catch (Exception e) {
                }
                files.add(dto);
            }

            return files;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                log.warn("MinIO unavailable, returning empty file list: {}", e.getMessage());
                return new ArrayList<>();
            }
            if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                log.warn("MinIO bucket missing, returning empty file list. Restart the app to auto-create bucket.");
                return new ArrayList<>();
            }
            throw new RuntimeException("Failed to list files: " + e.getMessage(), e);
        }
    }

    private static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return true;
            if (t.getMessage() != null && t.getMessage().contains("Connection refused")) return true;
        }
        return false;
    }

    public InputStream getFileContent(String objectKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file content: " + e.getMessage(), e);
        }
    }
}