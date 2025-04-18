package beckand.test.Controller;

import beckand.test.DTO.FileAttributesCreateDTO;
import beckand.test.DTO.FileDTO;
import beckand.test.Response.BaseResponse;
import beckand.test.Response.TalentIdException;
import beckand.test.Service.MinioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final MinioService minioService;

    @PostMapping("/upload")
    public BaseResponse<FileDTO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @ModelAttribute FileAttributesCreateDTO createDTO,
            BindingResult bindingResult,
            @RequestHeader("Authorization") String token) throws TalentIdException {
        return minioService.uploadFileToMinIO(file, createDTO, bindingResult, token);
    }

    @GetMapping("/download/{fileId}")
    public ResponseEntity<?> downloadFile(@PathVariable Integer fileId) throws TalentIdException {
        return minioService.downloadFileFromMinIO(fileId);
    }

    @DeleteMapping("/{fileId}")
    public BaseResponse<String> deleteFile(@PathVariable Integer fileId) throws TalentIdException {
        return minioService.deleteFileFromMinIO(fileId);
    }
}