package beckand.test.DTO.file;

import lombok.Data;

@Data
public class FileDTO {
    private String fileType;
    private String s3ObjectKey;
    private String fileName;
    private Long size;
}

