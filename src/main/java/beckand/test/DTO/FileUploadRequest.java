package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
@Schema(description = "Запрос на загрузку OBJ-модели")
public class FileUploadRequest {

    @Schema(description = "OBJ-файл модели (до 2 ГБ)", type = "string", format = "binary", required = true)
    private MultipartFile file;
}

