package beckand.test.DTO.course;

import lombok.Data;

@Data
public class CourseSummaryDto {
    private Integer id;
    private String title;
    private String description;
    private String coverUrl;
}

