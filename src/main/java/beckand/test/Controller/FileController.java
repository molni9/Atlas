package beckand.test.Controller;

import beckand.test.DTO.FileDTO;
import beckand.test.DTO.FileUploadRequest;
import beckand.test.Service.FileService;
import beckand.test.Service.ModelConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
@Tag(name = "Модели (Files)", description = "Загрузка OBJ-моделей, просмотр списка моделей, удаление и информация о файлах")
public class FileController {

    private final FileService fileService;
    private final ModelConversionService modelConversionService;

    @Operation(
            summary = "Загрузить модель (OBJ)",
            description = "Загружает OBJ-файл в хранилище MinIO. Поддерживаются большие файлы (до 2 ГБ). " +
                    "После загрузки модель появится в списке и будет доступна для просмотра и конвертации в glTF."
    )
    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<FileDTO> uploadFile(@ModelAttribute FileUploadRequest request) {
        return ResponseEntity.ok(fileService.uploadFile(request));
    }

    @Operation(
            summary = "Список моделей",
            description = "Возвращает список всех загруженных OBJ-моделей из MinIO. " +
                    "Для каждой модели: имя файла, размер, тип, ключ (s3ObjectKey) для запросов glTF и информации."
    )
    @GetMapping("")
    public ResponseEntity<List<FileDTO>> listFiles() {
        return ResponseEntity.ok(fileService.getAllFiles());
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

    @Operation(summary = "Получить glTF модель", description = "Конвертирует OBJ модель в glTF формат для клиентского рендеринга")
    @GetMapping("/{objectKey}/gltf")
    public ResponseEntity<String> getModelGltf(
            @Parameter(description = "Имя файла для конвертации", required = true)
            @PathVariable("objectKey") String objectKey
    ) {
        try {
            FileDTO info = fileService.getFileInfo(objectKey);
            if (!info.getFileType().contains("obj") && !objectKey.toLowerCase().endsWith(".obj")) {
                throw new IllegalArgumentException("Only OBJ files are supported for glTF conversion");
            }
            try (InputStream is = fileService.getFileContent(objectKey)) {
                byte[] modelBytes = is.readAllBytes();
                String gltfJson = modelConversionService.convertObjToGltf(objectKey, modelBytes);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Access-Control-Allow-Origin", "*")
                        .body(gltfJson);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error converting model to glTF: " + e.getMessage(), e);
        }
    }
}