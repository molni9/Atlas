package beckand.test.Repository.media;

import beckand.test.Model.media.ModelPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelPhotoRepository extends JpaRepository<ModelPhoto, Integer> {
    List<ModelPhoto> findByModelObjectKeyOrderByDisplayOrderAsc(String modelObjectKey);
}

