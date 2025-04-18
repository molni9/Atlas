package beckand.test.Mapper;

import beckand.test.DTO.FileDTO;
import beckand.test.Model.FileAttributes;
import org.springframework.stereotype.Component;

@Component
public class FileAttributesMapper {

    public FileDTO fileAttributesToFileDTO(FileAttributes fileAttributes) {
        FileDTO fileDTO = new FileDTO();
        fileDTO.setFileId(fileAttributes.getFileAttributesId());
        fileDTO.setFileName(fileAttributes.getFileName());
        fileDTO.setFileType(fileAttributes.getFileType());
        fileDTO.setFileSize(fileAttributes.getFileSize());
        fileDTO.setDescription(fileAttributes.getDescription());
        return fileDTO;
    }
}
