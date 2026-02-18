package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Срез модели")
public class SliceItemDto {
    @Schema(description = "ID записи")
    private Integer id;
    @Schema(description = "URL для получения изображения среза")
    private String url;
    @Schema(description = "Ось среза: x, y, z")
    private String axis;
    @Schema(description = "Индекс среза")
    private Integer sliceIndex;
    @Schema(description = "Порядок отображения")
    private Integer displayOrder;
}
