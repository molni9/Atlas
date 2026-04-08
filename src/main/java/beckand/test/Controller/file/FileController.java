package beckand.test.Controller.file;

import beckand.test.DTO.file.FileDTO;
import beckand.test.DTO.file.FileUploadRequest;
import beckand.test.Service.file.FileService;
import beckand.test.Service.render.RenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
@Tag(name = "File Controller", description = "Операции с файлами: загрузка, удаление, информация, рендер")
public class FileController {

    private final FileService fileService;
    private final RenderService renderService;

    @Operation(summary = "Загрузить файл", description = "Загружает файл с описанием")
    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<FileDTO> uploadFile(@ModelAttribute FileUploadRequest request) {
        return ResponseEntity.ok(fileService.uploadFile(request));
    }

    @Operation(summary = "Список файлов", description = "Возвращает список доступных файлов из MinIO")
    @GetMapping("")
    public ResponseEntity<List<FileDTO>> listFiles() {
        return ResponseEntity.ok(fileService.getAllFiles());
    }

    @Operation(summary = "Удалить файл", description = "Удаляет файл по имени")
    @DeleteMapping("/{objectKey:.+}")
    public ResponseEntity<Void> deleteFile(
            @Parameter(description = "Имя файла для удаления", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        fileService.deleteFile(objectKey);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Получить информацию о файле", description = "Возвращает информацию о файле по имени")
    @GetMapping("/{objectKey:.+}")
    public ResponseEntity<FileDTO> getFileInfo(
            @Parameter(description = "Имя файла для получения информации", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        return ResponseEntity.ok(fileService.getFileInfo(objectKey));
    }

    @Operation(
            summary = "OBJ для просмотра в браузере (WebGL)",
            description = "Отдаёт текст OBJ для клиентского Three.js. В продакшене ограничьте авторизацией — геометрия доступна для скачивания."
    )
    @GetMapping(value = "/viewer-obj", produces = "model/obj")
    public ResponseEntity<Resource> getObjForWebViewer(
            @Parameter(description = "Ключ объекта в хранилище", required = true)
            @RequestParam("key") String objectKey
    ) {
        FileDTO info = fileService.getFileInfo(objectKey);
        if (!isObjForViewer(info, objectKey)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        InputStream in = fileService.getFileContent(objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("model/obj"))
                .header("Cache-Control", "private, max-age=60")
                .body(new InputStreamResource(in));
    }

    private static boolean isObjForViewer(FileDTO info, String key) {
        if (key != null && key.toLowerCase().endsWith(".obj")) {
            return true;
        }
        String ct = info.getFileType();
        return ct != null && (ct.toLowerCase().contains("obj") || "model/obj".equalsIgnoreCase(ct.trim()));
    }

    @Operation(summary = "Получить рендер 3D модели", description = "Возвращает изображение рендера 3D модели")
    @GetMapping("/{objectKey:.+}/render")
    public ResponseEntity<byte[]> getModelRender(
            @Parameter(description = "Имя файла для рендеринга", required = true)
            @PathVariable("objectKey") String objectKey,
            @RequestParam(defaultValue = "0") double azimuth,
            @RequestParam(defaultValue = "0") double elevation
    ) {
        try {
            log.info("Rendering model: {}, azimuth: {}, elevation: {}", objectKey, azimuth, elevation);
            FileDTO info = fileService.getFileInfo(objectKey);
            try (InputStream is = fileService.getFileContent(objectKey)) {
                byte[] jpeg = renderService.renderModel(objectKey, is, info.getFileType(), azimuth, elevation);
                return ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(jpeg);
            }
        } catch (Exception e) {
            log.error("Error rendering model: {}", objectKey, e);
            throw new RuntimeException("Error rendering model: " + e.getMessage(), e);
        }
    }
}

