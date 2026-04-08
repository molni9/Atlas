package beckand.test.Service.course;

import beckand.test.DTO.course.CourseDetailDto;
import beckand.test.DTO.course.CourseSummaryDto;
import beckand.test.DTO.course.LessonDto;
import beckand.test.DTO.course.admin.CourseCreateRequest;
import beckand.test.DTO.course.admin.CourseUpdateRequest;
import beckand.test.Model.course.Course;
import beckand.test.Repository.course.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final LessonService lessonService;

    public java.util.List<CourseSummaryDto> listPublishedCourses() {
        return courseRepository.findByPublishedTrueOrderByCreatedAtDesc().stream()
                .map(this::toSummaryDto)
                .toList();
    }

    public Optional<CourseDetailDto> getPublishedCourseDetail(Integer courseId) {
        Optional<Course> courseOpt = courseRepository.findById(courseId).filter(Course::isPublished);
        if (courseOpt.isEmpty()) return Optional.empty();

        Course course = courseOpt.get();
        CourseDetailDto dto = new CourseDetailDto();
        dto.setId(course.getId());
        dto.setTitle(course.getTitle());
        dto.setDescription(course.getDescription());
        dto.setCoverUrl(course.getCoverUrl());

        java.util.List<LessonDto> lessons = lessonService.listPublishedLessonsByCourseId(courseId);
        dto.getLessons().addAll(lessons);
        return Optional.of(dto);
    }

    public Course createCourse(CourseCreateRequest req) {
        Course c = new Course();
        c.setTitle(req.getTitle());
        c.setDescription(req.getDescription());
        c.setCoverUrl(req.getCoverUrl());
        c.setPublished(false);
        return courseRepository.save(c);
    }

    public Optional<Course> updateCourse(Integer courseId, CourseUpdateRequest req) {
        return courseRepository.findById(courseId).map(c -> {
            c.setTitle(req.getTitle());
            c.setDescription(req.getDescription());
            c.setCoverUrl(req.getCoverUrl());
            if (req.getPublished() != null) c.setPublished(req.getPublished());
            return courseRepository.save(c);
        });
    }

    public Optional<Course> setCoursePublished(Integer courseId, boolean published) {
        return courseRepository.findById(courseId).map(c -> {
            c.setPublished(published);
            return courseRepository.save(c);
        });
    }

    private CourseSummaryDto toSummaryDto(Course c) {
        CourseSummaryDto dto = new CourseSummaryDto();
        dto.setId(c.getId());
        dto.setTitle(c.getTitle());
        dto.setDescription(c.getDescription());
        dto.setCoverUrl(c.getCoverUrl());
        return dto;
    }
}

