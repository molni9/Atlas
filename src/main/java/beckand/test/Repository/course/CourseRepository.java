package beckand.test.Repository.course;

import beckand.test.Model.course.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Integer> {
    List<Course> findByPublishedTrueOrderByCreatedAtDesc();
}

