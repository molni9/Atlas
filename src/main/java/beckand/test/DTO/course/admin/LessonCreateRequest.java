package beckand.test.DTO.course.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LessonCreateRequest {
    @NotNull
    private Integer courseId;

    @NotBlank
    @Size(max = 200)
    private String title;

    private Integer orderIndex;
}

