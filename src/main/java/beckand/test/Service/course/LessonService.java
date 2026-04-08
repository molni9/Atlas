package beckand.test.Service.course;

import beckand.test.DTO.course.LessonDto;
import beckand.test.DTO.course.admin.LessonCreateRequest;
import beckand.test.DTO.course.admin.LessonUpdateRequest;
import beckand.test.Model.course.Course;
import beckand.test.Model.course.Lesson;
import beckand.test.Repository.course.CourseRepository;
import beckand.test.Repository.course.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LessonService {

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;

    public List<LessonDto> listPublishedLessonsByCourseId(Integer courseId) {
        return lessonRepository.findByCourseIdAndPublishedTrueOrderByOrderIndexAsc(courseId).stream()
                .map(this::toDto)
                .toList();
    }

    private LessonDto toDto(Lesson lesson) {
        LessonDto dto = new LessonDto();
        dto.setId(lesson.getId());
        dto.setTitle(lesson.getTitle());
        dto.setOrderIndex(lesson.getOrderIndex());
        return dto;
    }

    public List<LessonDto> listLessonsForAdmin(Integer courseId) {
        return lessonRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(this::toDto)
                .toList();
    }

    public Optional<Lesson> createLesson(LessonCreateRequest req) {
        Course course = courseRepository.findById(req.getCourseId()).orElse(null);
        if (course == null) return Optional.empty();
        Lesson lesson = new Lesson();
        lesson.setCourse(course);
        lesson.setTitle(req.getTitle());
        lesson.setOrderIndex(req.getOrderIndex() != null ? req.getOrderIndex() : 0);
        lesson.setPublished(false);
        return Optional.of(lessonRepository.save(lesson));
    }

    public Optional<Lesson> updateLesson(Integer lessonId, LessonUpdateRequest req) {
        return lessonRepository.findById(lessonId).map(l -> {
            l.setTitle(req.getTitle());
            if (req.getOrderIndex() != null) l.setOrderIndex(req.getOrderIndex());
            if (req.getPublished() != null) l.setPublished(req.getPublished());
            return lessonRepository.save(l);
        });
    }

    public boolean deleteLesson(Integer lessonId) {
        if (!lessonRepository.existsById(lessonId)) return false;
        lessonRepository.deleteById(lessonId);
        return true;
    }
}

