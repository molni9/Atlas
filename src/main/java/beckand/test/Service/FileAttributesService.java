package beckand.test.Service;

import beckand.test.DTO.FileAttributesCreateDTO;
import beckand.test.Model.FileAttributes;
import beckand.test.Repository.FileAttributesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileAttributesService {

    private final FileAttributesRepository fileAttributesRepository;

    public FileAttributes createNewFileAttributes(FileAttributesCreateDTO createDTO, UUID playerId, MultipartFile file) {
        FileAttributes attributes = new FileAttributes();
        attributes.setPlayerId(playerId);
        attributes.setFileName(file.getOriginalFilename());
        attributes.setContentType(file.getContentType());
        attributes.setSize(file.getSize());

        try {
            FileAttributes savedAttributes = fileAttributesRepository.save(attributes);
            savedAttributes.setMinioFileName(generateMinioFileName(savedAttributes.getFileAttributesId(), file.getOriginalFilename()));
            savedAttributes.setBucketName("player-" + playerId);
            return fileAttributesRepository.save(savedAttributes);
        } catch (DataAccessException e) {
            throw new FileServiceException("Failed to save file attributes", e);
        }
    }

    private String generateMinioFileName(Integer id, String fileName) {
        return id + "_" + fileName;
    }

    public String receiveMinioFileNameById(Integer fileId) {
        try {
            return fileAttributesRepository.findById(fileId)
                    .map(FileAttributes::getMinioFileName)
                    .orElseThrow(() -> new FileNotFoundException("File not found with ID: " + fileId));
        } catch (DataAccessException e) {
            throw new FileServiceException("Database error while fetching file", e);
        }
    }

    public String receiveOriginalFileNameById(Integer fileId) {
        try {
            return fileAttributesRepository.findById(fileId)
                    .map(FileAttributes::getFileName)
                    .orElseThrow(() -> new FileNotFoundException("File not found with ID: " + fileId));
        } catch (DataAccessException e) {
            throw new FileServiceException("Database error while fetching file", e);
        }
    }

    public String receiveBucketNameById(Integer fileId) {
        try {
            return fileAttributesRepository.findById(fileId)
                    .map(FileAttributes::getBucketName)
                    .orElseThrow(() -> new FileNotFoundException("File not found with ID: " + fileId));
        } catch (DataAccessException e) {
            throw new FileServiceException("Database error while fetching file", e);
        }
    }

    public void deleteFileAttributesById(Integer fileId) {
        try {
            if (!fileAttributesRepository.existsById(fileId)) {
                throw new FileNotFoundException("File not found with ID: " + fileId);
            }
            fileAttributesRepository.deleteById(fileId);
        } catch (DataAccessException e) {
            throw new FileServiceException("Database error while deleting file", e);
        }
    }

    // Custom exceptions
    public static class FileServiceException extends RuntimeException {
        public FileServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }
}