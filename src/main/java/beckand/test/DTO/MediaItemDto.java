package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Элемент медиа (фото или видео)")
public class MediaItemDto {
    @Schema(description = "ID записи")
    private Integer id;
    @Schema(description = "URL для получения файла (GET /files/{modelKey}/media/photo/{id} или .../video/{id})")
    private String url;
    @Schema(description = "Порядок отображения")
    private Integer displayOrder;
}
