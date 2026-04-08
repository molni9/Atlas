package beckand.test.DTO.media;

import lombok.Data;

@Data
public class SliceItemDto {
    private Integer id;
    private String axis;
    private Integer sliceIndex;
    private Integer displayOrder;
    private String url;
}

