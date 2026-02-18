package beckand.test.Model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "model_slice")
public class ModelSlice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "model_object_key", nullable = false)
    private String modelObjectKey;
    @Column(name = "s3_key", nullable = false)
    private String s3Key;
    /** Ось среза: x, y, z */
    private String axis;
    @Column(name = "slice_index")
    private Integer sliceIndex = 0;
    @Column(name = "display_order")
    private Integer displayOrder = 0;
}
