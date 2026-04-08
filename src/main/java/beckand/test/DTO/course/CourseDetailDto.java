package beckand.test.DTO.course;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CourseDetailDto {
    private Integer id;
    private String title;
    private String description;
    private String coverUrl;
    private List<LessonDto> lessons = new ArrayList<>();
}

