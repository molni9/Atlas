package beckand.test.Repository.course;

import beckand.test.Model.course.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, Integer> {
    List<Lesson> findByCourseIdAndPublishedTrueOrderByOrderIndexAsc(Integer courseId);
    List<Lesson> findByCourseIdOrderByOrderIndexAsc(Integer courseId);
}

