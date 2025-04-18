package beckand.test.DTO;

import lombok.Data;

@Data
public class FileDTO {
    private Integer fileId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String description;
}
