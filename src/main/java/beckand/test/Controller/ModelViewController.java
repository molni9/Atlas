package beckand.test.controller;

import beckand.test.Model.FileAttributes;
import beckand.test.Repository.FileAttributesRepository;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/model-viewer")
public class ModelViewController {

    private static final Logger logger = LoggerFactory.getLogger(ModelViewController.class);
    private final FileAttributesRepository fileAttributesRepository;
    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String minioBucket;

    public ModelViewController(FileAttributesRepository fileAttributesRepository, MinioClient minioClient) {
        this.fileAttributesRepository = fileAttributesRepository;
        this.minioClient = minioClient;
        logger.info("ModelViewController initialized with Minio bucket: {}", minioBucket);
    }

    @GetMapping
    public String getViewerPage() {
        logger.debug("Serving model viewer page");
        return "model-viewer";
    }

    @GetMapping("/models")
    @ResponseBody
    public ResponseEntity<?> getModelList() {
        try {
            logger.info("Fetching list of 3D models from database");
            
            List<FileAttributes> models = fileAttributesRepository.findAll();
            logger.debug("Retrieved {} total files from database", models.size());
            
            List<FileAttributes> modelFiles = models.stream()
                    .filter(file -> {
                        String fileName = file.getFileName().toLowerCase();
                        return fileName.endsWith(".glb") || 
                               fileName.endsWith(".gltf") || 
                               fileName.endsWith(".obj");
                    })
                    .collect(Collectors.toList());

            logger.info("Found {} 3D models in database", modelFiles.size());
            
            if (modelFiles.isEmpty()) {
                logger.warn("No 3D models found in database");
                return ResponseEntity.status(HttpStatus.NO_CONTENT)
                        .body("No 3D models found in database");
            }

            return ResponseEntity.ok(modelFiles);
        } catch (Exception e) {
            logger.error("Error getting model list from database: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error retrieving model list: " + e.getMessage());
        }
    }

    @GetMapping("/model/{id}")
    @ResponseBody
    public ResponseEntity<?> getModel(@PathVariable Integer id) {
        try {
            logger.info("Attempting to load model with ID: {}", id);
            
            FileAttributes fileAttributes = fileAttributesRepository.findById(id)
                    .orElseThrow(() -> {
                        logger.warn("Model not found in database with ID: {}", id);
                        return new RuntimeException("Model not found with ID: " + id);
                    });

            logger.debug("Found model in database: {}", fileAttributes.getFileName());
            
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioBucket)
                            .object(fileAttributes.getS3ObjectKey())
                            .build());

            logger.info("Successfully loaded model from MinIO: {}", fileAttributes.getFileName());
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new InputStreamResource(stream));
        } catch (MinioException e) {
            logger.error("MinIO error while loading model with ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error loading model from storage: " + e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Model not found with ID {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error while loading model with ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Unexpected error: " + e.getMessage());
        }
    }
} 