package beckand.test.Mapper;

import beckand.test.DTO.file.FileDTO;
import beckand.test.Model.file.FileAttributes;
import org.springframework.stereotype.Component;

@Component
public class FileMapper {

    public FileDTO toDTO(FileAttributes entity) {
        FileDTO fileDTO = new FileDTO();
        fileDTO.setFileType(entity.getContentType());
        fileDTO.setS3ObjectKey(entity.getS3ObjectKey());
        return fileDTO;
    }
}
