package beckand.test.Repository.file;

import beckand.test.Model.file.FileAttributes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FileAttributesRepository extends JpaRepository<FileAttributes, Integer> {
    Optional<FileAttributes> findByS3ObjectKey(String s3ObjectKey);
}

