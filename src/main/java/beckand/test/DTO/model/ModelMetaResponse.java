package beckand.test.DTO.model;

import beckand.test.DTO.media.MediaItemDto;
import beckand.test.DTO.media.SliceItemDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ModelMetaResponse {
    private String description;
    private List<MediaItemDto> photos = new ArrayList<>();
    private List<MediaItemDto> videos = new ArrayList<>();
    private List<SliceItemDto> slices = new ArrayList<>();
}

