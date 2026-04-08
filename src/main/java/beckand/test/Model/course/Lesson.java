package beckand.test.Model.course;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "lesson", indexes = {
        @Index(name = "idx_lesson_course_order", columnList = "course_id, order_index")
})
public class Lesson {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "order_index", nullable = false)
    private int orderIndex = 0;

    @Column(nullable = false)
    private boolean published = false;
}

