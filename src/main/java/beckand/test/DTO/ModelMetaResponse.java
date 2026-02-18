package beckand.test.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Метаданные модели: описание, фото, видео, срезы")
public class ModelMetaResponse {
    @Schema(description = "Описание модели")
    private String description;
    @Schema(description = "Фото модели")
    private List<MediaItemDto> photos = new ArrayList<>();
    @Schema(description = "Видео модели")
    private List<MediaItemDto> videos = new ArrayList<>();
    @Schema(description = "Срезы модели")
    private List<SliceItemDto> slices = new ArrayList<>();
}
