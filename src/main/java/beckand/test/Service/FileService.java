package beckand.test.Service;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import beckand.test.Mapper.FileMapper;
import beckand.test.Model.FileAttributes;
import beckand.test.Repository.FileAttributesRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FileService {

    private final MinioClient minioClient;
    private final FileAttributesRepository repository;
    private final FileMapper mapper;

    @Value("${minio.bucket}")
    private String bucket;

    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    io.minio.BucketExistsArgs.builder()
                            .bucket(bucket)
                            .build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        io.minio.MakeBucketArgs.builder()
                                .bucket(bucket)
                                .build()
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot create or access bucket", e);
        }
    }

    public FileDTO uploadFile(FileUploadRequest request) {
        try (InputStream is = request.getFile().getInputStream()) {
            String fileName = request.getFile().getOriginalFilename();

            // Загружаем файл в MinIO
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(fileName)
                            .stream(is, request.getFile().getSize(), -1)
                            .contentType(request.getFile().getContentType())
                            .build()
            );


            FileAttributes attributes = new FileAttributes();
            attributes.setFileName(fileName);
            attributes.setContentType(request.getFile().getContentType());
            attributes.setSize(request.getFile().getSize());
            attributes.setDescription(request.getDescription());
            attributes.setS3ObjectKey(fileName); // Сохраняем ключ объекта для работы с MinIO

            FileAttributes saved = repository.save(attributes);

            return mapper.toDTO(saved);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload file", e);
        }
    }

    public void deleteFile(Integer id) {
        FileAttributes entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(entity.getS3ObjectKey())  // Удаляем объект по ключу S3
                    .build());

            repository.delete(entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete file", e);
        }
    }

    public FileDTO getFileInfo(Integer id) {
        FileAttributes entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        return mapper.toDTO(entity);
    }
}
