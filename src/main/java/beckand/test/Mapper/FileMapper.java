package beckand.test.Mapper;

import beckand.test.DTO.FileDTO;
import beckand.test.Model.FileAttributes;
import org.springframework.stereotype.Component;

@Component
public class FileMapper {

    public FileDTO toDTO(FileAttributes entity) {
        FileDTO fileDTO = new FileDTO();
        fileDTO.setFileAttributesId(entity.getFileAttributesId());
        fileDTO.setFileName(entity.getFileName());
        fileDTO.setFileType(entity.getContentType());
        fileDTO.setFileSize(entity.getSize());
        fileDTO.setDescription(entity.getDescription());
        fileDTO.setS3ObjectKey(entity.getS3ObjectKey());
        return fileDTO;
    }
}
