package beckand.test.DTO.course.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LessonUpdateRequest {
    @NotBlank
    @Size(max = 200)
    private String title;

    private Integer orderIndex;
    private Boolean published;
}

