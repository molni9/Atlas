package beckand.test.Repository;

import beckand.test.Model.ModelSlice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelSliceRepository extends JpaRepository<ModelSlice, Integer> {
    List<ModelSlice> findByModelObjectKeyOrderByDisplayOrderAsc(String modelObjectKey);
}
