package beckand.test.Controller.admin;

import beckand.test.DTO.course.admin.CourseCreateRequest;
import beckand.test.DTO.course.admin.CourseUpdateRequest;
import beckand.test.DTO.course.admin.LessonCreateRequest;
import beckand.test.DTO.course.admin.LessonUpdateRequest;
import beckand.test.DTO.course.LessonDto;
import beckand.test.Model.course.Course;
import beckand.test.Model.course.Lesson;
import beckand.test.Service.course.CourseService;
import beckand.test.Service.course.LessonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/courses")
@Tag(name = "Admin Courses", description = "CRUD курсов и уроков (пока без авторизации)")
public class AdminCourseController {

    private final CourseService courseService;
    private final LessonService lessonService;

    @Operation(summary = "Создать курс")
    @PostMapping
    public ResponseEntity<Course> createCourse(@Valid @RequestBody CourseCreateRequest req) {
        return ResponseEntity.ok(courseService.createCourse(req));
    }

    @Operation(summary = "Обновить курс")
    @PutMapping("/{courseId}")
    public ResponseEntity<Course> updateCourse(@PathVariable Integer courseId, @Valid @RequestBody CourseUpdateRequest req) {
        return courseService.updateCourse(courseId, req)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Опубликовать/снять с публикации курс")
    @PostMapping("/{courseId}/publish")
    public ResponseEntity<Course> setPublished(@PathVariable Integer courseId, @RequestParam boolean published) {
        return courseService.setCoursePublished(courseId, published)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Список уроков курса (админ)")
    @GetMapping("/{courseId}/lessons")
    public List<LessonDto> listLessons(@PathVariable Integer courseId) {
        return lessonService.listLessonsForAdmin(courseId);
    }

    @Operation(summary = "Создать урок")
    @PostMapping("/{courseId}/lessons")
    public ResponseEntity<Lesson> createLesson(@PathVariable Integer courseId, @Valid @RequestBody LessonCreateRequest req) {
        req.setCourseId(courseId);
        return lessonService.createLesson(req)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Обновить урок")
    @PutMapping("/lessons/{lessonId}")
    public ResponseEntity<Lesson> updateLesson(@PathVariable Integer lessonId, @Valid @RequestBody LessonUpdateRequest req) {
        return lessonService.updateLesson(lessonId, req)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Удалить урок")
    @DeleteMapping("/lessons/{lessonId}")
    public ResponseEntity<Void> deleteLesson(@PathVariable Integer lessonId) {
        return lessonService.deleteLesson(lessonId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}

