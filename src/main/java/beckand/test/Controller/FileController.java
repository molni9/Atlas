package beckand.test.Controller;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import beckand.test.Service.FileService;
import beckand.test.Service.LwjglEglRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
@Tag(name = "File Controller", description = "Операции с файлами: загрузка, удаление, информация")
public class FileController {

    private final FileService fileService;
    private final ObjectProvider<LwjglEglRenderer> eglRendererProvider;

    @Operation(summary = "Загрузить файл", description = "Загружает файл с описанием")
    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<FileDTO> uploadFile(@ModelAttribute FileUploadRequest request) {
        return ResponseEntity.ok(fileService.uploadFile(request));
    }

    @Operation(summary = "Удалить файл", description = "Удаляет файл по имени")
    @DeleteMapping("/{objectKey}")
    public ResponseEntity<Void> deleteFile(
            @Parameter(description = "Имя файла для удаления", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        fileService.deleteFile(objectKey);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Получить информацию о файле", description = "Возвращает информацию о файле по имени")
    @GetMapping("/{objectKey}")
    public ResponseEntity<FileDTO> getFileInfo(
            @Parameter(description = "Имя файла для получения информации", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        return ResponseEntity.ok(fileService.getFileInfo(objectKey));
    }

    @Operation(summary = "Получить содержимое файла", description = "Возвращает содержимое файла по имени")
    @GetMapping("/{objectKey}/content")
    public ResponseEntity<InputStreamResource> getFileContent(
            @Parameter(description = "Имя файла для получения содержимого", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        try {
            FileDTO fileInfo = fileService.getFileInfo(objectKey);
            InputStream stream = fileService.getFileContent(objectKey);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(fileInfo.getFileType()))
                    .header("Content-Disposition", "inline")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET")
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            log.error("Error getting file content: {}", objectKey, e);
            throw new RuntimeException("Error getting file content: " + e.getMessage(), e);
        }
    }

    @Operation(summary = "Получить рендер 3D модели", description = "Возвращает изображение рендера 3D модели")
    @GetMapping("/{objectKey}/render")
    public ResponseEntity<byte[]> getModelRender(
            @Parameter(description = "Имя файла для рендеринга", required = true)
            @PathVariable("objectKey") String objectKey,
            @RequestParam(defaultValue = "0") double azimuth,
            @RequestParam(defaultValue = "0") double elevation
    ) {
        try {
            log.info("Rendering model: {}, azimuth: {}, elevation: {}", objectKey, azimuth, elevation);
            LwjglEglRenderer eglRenderer = eglRendererProvider.getIfAvailable();
            if (eglRenderer != null) {
                byte[] imageData = eglRenderer.renderFrame(960, 540, azimuth, elevation);
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(imageData);
            }
            // Fallback: простая заглушка PNG
            BufferedImage bi = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 800, 450);
            g.setColor(Color.DARK_GRAY);
            g.drawString("GPU renderer disabled", 20, 30);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "PNG", baos);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(baos.toByteArray());
        } catch (Exception e) {
            log.error("Error rendering model: {}", objectKey, e);
            throw new RuntimeException("Error rendering model: " + e.getMessage(), e);
        }
    }
}