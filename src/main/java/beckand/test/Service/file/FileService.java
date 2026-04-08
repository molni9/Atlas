package beckand.test.Service.file;

import beckand.test.DTO.file.FileDTO;
import beckand.test.DTO.file.FileUploadRequest;
import beckand.test.Model.file.FileAttributes;
import beckand.test.Repository.file.FileAttributesRepository;
import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final MinioClient minioClient;
    private final FileAttributesRepository fileAttributesRepository;
    private final ModelMediaService modelMediaService;

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
            modelMediaService.deleteAllMediaForModel(objectKey);
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

            FileDTO dto = new FileDTO();
            dto.setS3ObjectKey(objectKey);
            dto.setFileType(stat.contentType());
            dto.setFileName(objectKey);
            dto.setSize(stat.size());
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file info: " + e.getMessage(), e);
        }
    }

    public InputStream getFileContent(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file content: " + e.getMessage(), e);
        }
    }

    public List<FileDTO> getAllFiles() {
        List<FileDTO> files = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> r : results) {
                Item item = r.get();
                if (item.isDir()) continue;
                FileDTO dto = new FileDTO();
                dto.setS3ObjectKey(item.objectName());
                dto.setFileName(item.objectName());
                files.add(dto);
            }
            return files;
        } catch (Exception e) {
            if (e.getCause() instanceof ConnectException) {
                log.warn("MinIO недоступен: {}", e.getMessage());
                return files;
            }
            throw new RuntimeException("Failed to list files: " + e.getMessage(), e);
        }
    }

    public Optional<FileAttributes> getFileAttributes(String objectKey) {
        return fileAttributesRepository.findByS3ObjectKey(objectKey);
    }
}

