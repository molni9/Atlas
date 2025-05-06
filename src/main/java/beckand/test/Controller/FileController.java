package beckand.test.Controller;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import beckand.test.Service.FileService;
import beckand.test.Service.RenderService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
@Tag(name = "File Controller", description = "Операции с файлами: загрузка, удаление, информация")
public class FileController {

    private final FileService fileService;
    private final MinioClient minioClient;
    private final RenderService renderService;

    @Value("${minio.bucket}")
    private String bucket;

    @Operation(summary = "Загрузить файл", description = "Загружает файл с описанием")
    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<FileDTO> uploadFile(@ModelAttribute FileUploadRequest request) {
        return ResponseEntity.ok(fileService.uploadFile(request));
    }

    @Operation(summary = "Удалить файл", description = "Удаляет файл по ID")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @Parameter(description = "ID файла, который нужно удалить", required = true)
            @PathVariable("id") Integer id
    ) {
        fileService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Получить информацию о файле", description = "Возвращает информацию о файле по ID")
    @GetMapping("/{id}")
    public ResponseEntity<FileDTO> getFileInfo(
            @Parameter(description = "ID файла для получения информации", required = true)
            @PathVariable("id") Integer id
    ) {
        return ResponseEntity.ok(fileService.getFileInfo(id));
    }

    @Operation(summary = "Получить содержимое файла", description = "Возвращает содержимое файла по ID")
    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> getFileContent(
            @Parameter(description = "ID файла для получения содержимого", required = true)
            @PathVariable("id") Integer id
    ) {
        try {
            FileDTO fileInfo = fileService.getFileInfo(id);
            
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(fileInfo.getS3ObjectKey())
                            .build());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(fileInfo.getFileType()))
                    .header("Content-Disposition", "inline")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET")
                    .body(new InputStreamResource(stream));
        } catch (MinioException e) {
            throw new RuntimeException("Error getting file content: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    @Operation(summary = "Получить список всех файлов", description = "Возвращает список всех файлов")
    @GetMapping
    public ResponseEntity<List<FileDTO>> getAllFiles() {
        return ResponseEntity.ok(fileService.getAllFiles());
    }

    @Operation(summary = "Получить рендер 3D модели", description = "Возвращает изображение рендера 3D модели")
    @GetMapping("/{id}/render")
    public ResponseEntity<byte[]> getModelRender(
            @Parameter(description = "ID файла для рендеринга", required = true)
            @PathVariable("id") Integer id,
            @RequestParam(defaultValue = "0") double azimuth,
            @RequestParam(defaultValue = "0") double elevation
    ) {
        try {
            FileDTO fileInfo = fileService.getFileInfo(id);
            // Получаем модель из MinIO
            InputStream modelStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(fileInfo.getS3ObjectKey())
                            .build());
            // Рендерим модель с учетом углов
            byte[] imageData = renderService.renderModel(modelStream, fileInfo.getFileType(), azimuth, elevation);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(imageData);
        } catch (Exception e) {
            throw new RuntimeException("Error rendering model: " + e.getMessage(), e);
        }
    }
}