package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Обновление описания модели")
public class ModelMetaUpdateRequest {
    @Schema(description = "Новое описание модели")
    private String description;
}
