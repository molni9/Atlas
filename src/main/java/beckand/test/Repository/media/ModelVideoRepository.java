package beckand.test.Repository.media;

import beckand.test.Model.media.ModelVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelVideoRepository extends JpaRepository<ModelVideo, Integer> {
    List<ModelVideo> findByModelObjectKeyOrderByDisplayOrderAsc(String modelObjectKey);
}

