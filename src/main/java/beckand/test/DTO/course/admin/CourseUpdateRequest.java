package beckand.test.DTO.course.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CourseUpdateRequest {
    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 4000)
    private String description;

    @Size(max = 2048)
    private String coverUrl;

    private Boolean published;
}

