package beckand.test.Service;

import beckand.test.DTO.FileAttributesCreateDTO;
import beckand.test.DTO.FileDTO;
import beckand.test.Model.FileAttributes;
import beckand.test.Response.BaseResponse;
import beckand.test.Response.TalentIdException;
import io.minio.*;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;


import beckand.test.Utils.JwtUtils;
import beckand.test.validator.MinioValidator;
import beckand.test.Mapper.FileAttributesMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements MinioService {
    private final MinioClient minioClient;
    private final FileAttributesService fileAttributesServiceImpl;
    private final FileAttributesMapper fileAttributesMapper;
    private final MinioValidator minioValidator;
    private final JwtUtils jwtUtils;

    @Override
    public void checkExistAndCreateBucket(UUID playerId) {
        try {
            String bucketName = String.format("player-%d", playerId);
            if(!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucketName)
                                .build());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public BaseResponse<FileDTO> uploadFileToMinIO(MultipartFile file, FileAttributesCreateDTO fileAttributesCreateDTO, BindingResult bindingResult, String bearerToken) throws TalentIdException {
        var baseResponse = new BaseResponse<FileDTO>();
        UUID playerId = jwtUtils.getUserId(bearerToken);
        minioValidator.checkUploadFileToMinIO(bindingResult);
        try (InputStream inputStream = new ByteArrayInputStream(file.getBytes())) {
            FileAttributes fileAttributes = fileAttributesServiceImpl.createNewFileAttributes(fileAttributesCreateDTO, playerId, file);
            this.checkExistAndCreateBucket(fileAttributes.getPlayerId());
            String minioFileName = String.format("%d_%s", fileAttributes.getFileAttributesId(), fileAttributes.getFileName());
            String minioBucketName = String.format("player-%d", fileAttributes.getPlayerId());
            minioClient.putObject(PutObjectArgs.builder().bucket(minioBucketName).object(minioFileName)
                    .stream(inputStream, -1, 10485760).build());
            baseResponse.ok().body(fileAttributesMapper.fileAttributesToFileDTO(fileAttributes));
        } catch (IOException | ErrorResponseException | InsufficientDataException | InternalException |
                 InvalidKeyException | InvalidResponseException | NoSuchAlgorithmException | ServerException |
                 XmlParserException e) {
            var exception = new TalentIdException(HttpStatus.BAD_REQUEST);
            exception.setExactMessage(e.getMessage());
            throw exception;
        }
        return baseResponse;
    }

    @Override
    public ResponseEntity<ByteArrayResource> downloadFileFromMinIO(Integer fileId) throws TalentIdException {
        String minioFileName = fileAttributesServiceImpl.receiveMinioFileNameById(fileId);
        String originFileName = fileAttributesServiceImpl.receiveOriginalFileNameById(fileId);
        String bucketName = fileAttributesServiceImpl.receiveBucketNameById(fileId);
        try (InputStream stream = minioClient
                .getObject(GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(minioFileName)
                        .build());) {
            byte[] content = IOUtils.toByteArray(stream);
            ByteArrayResource resource = new ByteArrayResource(content);
            return ResponseEntity
                    .ok()
                    .contentLength(content.length)
                    .header("Content-type", "application/octet-stream")
                    .header("Content-disposition", String.format("attachment; filename=\"%s\"", originFileName))
                    .body(resource);

        } catch (IOException | ErrorResponseException | InsufficientDataException | InternalException |
                 InvalidKeyException | InvalidResponseException | NoSuchAlgorithmException | ServerException |
                 XmlParserException e) {
            var exception = new TalentIdException(HttpStatus.BAD_REQUEST);
            exception.setExactMessage(e.getMessage());
            throw exception;
        }
    }

    @Override
    public BaseResponse<String> deleteFileFromMinIO(Integer fileId) throws TalentIdException {
        var baseResponse = new BaseResponse<String>();
        try {
            String minioFileName = fileAttributesServiceImpl.receiveMinioFileNameById(fileId);
            String bucketName = fileAttributesServiceImpl.receiveBucketNameById(fileId);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(minioFileName)
                    .build());
            fileAttributesServiceImpl.deleteFileAttributesById(fileId);
            baseResponse.ok().body(String.format("file %d is deleted", fileId));
            return baseResponse;

        } catch (IOException | ErrorResponseException | InsufficientDataException | InternalException |
                 InvalidKeyException | InvalidResponseException | NoSuchAlgorithmException | ServerException |
                 XmlParserException e) {
            e.printStackTrace();
            var exception = new TalentIdException(HttpStatus.BAD_REQUEST);
            exception.setExactMessage(e.getMessage());
            throw exception;
        }
    }
}

