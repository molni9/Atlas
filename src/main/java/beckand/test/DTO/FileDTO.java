package beckand.test.DTO;

import lombok.Data;

@Data
public class FileDTO {
    private String fileType;
    private String s3ObjectKey;
}
