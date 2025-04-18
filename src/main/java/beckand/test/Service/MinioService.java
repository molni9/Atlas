package beckand.test.Service;


import beckand.test.DTO.FileAttributesCreateDTO;
import beckand.test.DTO.FileDTO;
import beckand.test.Response.BaseResponse;
import beckand.test.Response.TalentIdException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;



import java.util.UUID;


public interface MinioService {
    void checkExistAndCreateBucket(UUID playerId);
    BaseResponse<FileDTO> uploadFileToMinIO(MultipartFile file, FileAttributesCreateDTO fileAttributesCreateDTO, BindingResult bindingResult, String bearerToken) throws TalentIdException;
    ResponseEntity<?> downloadFileFromMinIO(Integer fileId) throws TalentIdException;
    BaseResponse<String> deleteFileFromMinIO(Integer fileId) throws TalentIdException;
}
