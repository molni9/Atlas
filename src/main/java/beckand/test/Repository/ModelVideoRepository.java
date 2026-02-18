package beckand.test.Repository;

import beckand.test.Model.ModelVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelVideoRepository extends JpaRepository<ModelVideo, Integer> {
    List<ModelVideo> findByModelObjectKeyOrderByDisplayOrderAsc(String modelObjectKey);
}
