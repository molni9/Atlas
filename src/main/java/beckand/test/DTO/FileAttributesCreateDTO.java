package beckand.test.DTO;

import lombok.Data;

@Data
public class FileAttributesCreateDTO {
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String description;
}
