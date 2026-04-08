package beckand.test.Controller.course;

import beckand.test.DTO.course.CourseDetailDto;
import beckand.test.DTO.course.CourseSummaryDto;
import beckand.test.DTO.course.LessonDto;
import beckand.test.Service.course.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/courses")
@Tag(name = "Courses", description = "Публичный каталог курсов")
public class CourseController {

    private final CourseService courseService;

    @Operation(summary = "Список опубликованных курсов")
    @GetMapping
    public List<CourseSummaryDto> listPublishedCourses() {
        return courseService.listPublishedCourses();
    }

    @Operation(summary = "Карточка курса + опубликованные уроки")
    @GetMapping("/{courseId}")
    public ResponseEntity<CourseDetailDto> getCourse(@PathVariable Integer courseId) {
        return courseService.getPublishedCourseDetail(courseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

