package beckand.test.Repository.media;

import beckand.test.Model.media.ModelSlice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelSliceRepository extends JpaRepository<ModelSlice, Integer> {
    List<ModelSlice> findByModelObjectKeyOrderByDisplayOrderAsc(String modelObjectKey);
}

