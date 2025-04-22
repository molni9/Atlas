package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileUploadRequest {

    @Schema(description = "Файл для загрузки", type = "string", format = "binary", required = true)
    private MultipartFile file;

    @Schema(description = "Описание файла", required = true)
    private String description;
}

