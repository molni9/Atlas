package beckand.test.Model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "model_video")
public class ModelVideo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "model_object_key", nullable = false)
    private String modelObjectKey;
    @Column(name = "s3_key", nullable = false)
    private String s3Key;
    @Column(name = "display_order")
    private Integer displayOrder = 0;
}
