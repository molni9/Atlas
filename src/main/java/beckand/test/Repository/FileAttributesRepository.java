package beckand.test.Repository;

import beckand.test.Model.FileAttributes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


public interface FileAttributesRepository extends JpaRepository<FileAttributes, Integer> {
}
