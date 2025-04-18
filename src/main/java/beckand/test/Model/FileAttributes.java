package beckand.test.Model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
public class FileAttributes {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer fileAttributesId;

    private UUID playerId;
    private String fileName;
    private String contentType;
    private Long size;
    private String fileType;//
    private Long fileSize;//
    private String description;
    private String minioFileName;//
    private String bucketName;//
}
