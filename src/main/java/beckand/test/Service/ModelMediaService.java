package beckand.test.Service;

import beckand.test.DTO.MediaItemDto;
import beckand.test.DTO.ModelMetaResponse;
import beckand.test.DTO.SliceItemDto;
import beckand.test.Model.FileAttributes;
import beckand.test.Model.ModelPhoto;
import beckand.test.Model.ModelSlice;
import beckand.test.Model.ModelVideo;
import beckand.test.Repository.FileAttributesRepository;
import beckand.test.Repository.ModelPhotoRepository;
import beckand.test.Repository.ModelSliceRepository;
import beckand.test.Repository.ModelVideoRepository;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelMediaService {

    private final MinioClient minioClient;
    private final FileAttributesRepository fileAttributesRepository;
    private final ModelPhotoRepository modelPhotoRepository;
    private final ModelVideoRepository modelVideoRepository;
    private final ModelSliceRepository modelSliceRepository;

    @Value("${minio.bucket}")
    private String bucket;

    private static final String MEDIA_PREFIX = "media/";

    public ModelMetaResponse getMeta(String modelObjectKey) {
        ModelMetaResponse response = new ModelMetaResponse();
        fileAttributesRepository.findByS3ObjectKey(modelObjectKey)
                .ifPresent(fa -> response.setDescription(fa.getDescription()));

        for (ModelPhoto p : modelPhotoRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)) {
            MediaItemDto dto = new MediaItemDto();
            dto.setId(p.getId());
            dto.setDisplayOrder(p.getDisplayOrder());
            response.getPhotos().add(dto);
        }
        for (ModelVideo v : modelVideoRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)) {
            MediaItemDto dto = new MediaItemDto();
            dto.setId(v.getId());
            dto.setDisplayOrder(v.getDisplayOrder());
            response.getVideos().add(dto);
        }
        for (ModelSlice s : modelSliceRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)) {
            SliceItemDto dto = new SliceItemDto();
            dto.setId(s.getId());
            dto.setAxis(s.getAxis());
            dto.setSliceIndex(s.getSliceIndex());
            dto.setDisplayOrder(s.getDisplayOrder());
            response.getSlices().add(dto);
        }
        return response;
    }

    public void updateDescription(String modelObjectKey, String description) {
        FileAttributes fa = fileAttributesRepository.findByS3ObjectKey(modelObjectKey)
                .orElseGet(() -> {
                    FileAttributes newFa = new FileAttributes();
                    newFa.setS3ObjectKey(modelObjectKey);
                    return newFa;
                });
        fa.setDescription(description);
        fileAttributesRepository.save(fa);
    }

    public MediaItemDto uploadPhoto(String modelObjectKey, MultipartFile file) {
        String ext = getExtension(file.getOriginalFilename(), "jpg");
        String s3Key = MEDIA_PREFIX + "photos/" + UUID.randomUUID() + ext;
        putToMinio(s3Key, file);
        ModelPhoto p = new ModelPhoto();
        p.setModelObjectKey(modelObjectKey);
        p.setS3Key(s3Key);
        p.setDisplayOrder(0);
        p = modelPhotoRepository.save(p);
        MediaItemDto dto = new MediaItemDto();
        dto.setId(p.getId());
        dto.setDisplayOrder(p.getDisplayOrder());
        return dto;
    }

    public MediaItemDto uploadVideo(String modelObjectKey, MultipartFile file) {
        String ext = getExtension(file.getOriginalFilename(), "mp4");
        String s3Key = MEDIA_PREFIX + "videos/" + UUID.randomUUID() + ext;
        putToMinio(s3Key, file);
        ModelVideo v = new ModelVideo();
        v.setModelObjectKey(modelObjectKey);
        v.setS3Key(s3Key);
        v.setDisplayOrder(0);
        v = modelVideoRepository.save(v);
        MediaItemDto dto = new MediaItemDto();
        dto.setId(v.getId());
        dto.setDisplayOrder(v.getDisplayOrder());
        return dto;
    }

    public SliceItemDto uploadSlice(String modelObjectKey, MultipartFile file, String axis, Integer sliceIndex) {
        String ext = getExtension(file.getOriginalFilename(), "png");
        String s3Key = MEDIA_PREFIX + "slices/" + UUID.randomUUID() + ext;
        putToMinio(s3Key, file);
        ModelSlice s = new ModelSlice();
        s.setModelObjectKey(modelObjectKey);
        s.setS3Key(s3Key);
        s.setAxis(axis != null ? axis : "z");
        s.setSliceIndex(sliceIndex != null ? sliceIndex : 0);
        s.setDisplayOrder(0);
        s = modelSliceRepository.save(s);
        SliceItemDto dto = new SliceItemDto();
        dto.setId(s.getId());
        dto.setAxis(s.getAxis());
        dto.setSliceIndex(s.getSliceIndex());
        dto.setDisplayOrder(s.getDisplayOrder());
        return dto;
    }

    public void deletePhoto(Integer id) {
        modelPhotoRepository.findById(id).ifPresent(p -> {
            removeFromMinio(p.getS3Key());
            modelPhotoRepository.delete(p);
        });
    }

    public void deleteVideo(Integer id) {
        modelVideoRepository.findById(id).ifPresent(v -> {
            removeFromMinio(v.getS3Key());
            modelVideoRepository.delete(v);
        });
    }

    public void deleteSlice(Integer id) {
        modelSliceRepository.findById(id).ifPresent(s -> {
            removeFromMinio(s.getS3Key());
            modelSliceRepository.delete(s);
        });
    }

    public InputStream getPhotoContent(Integer id) {
        ModelPhoto p = modelPhotoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Photo not found: " + id));
        return getFromMinio(p.getS3Key());
    }

    public InputStream getVideoContent(Integer id) {
        ModelVideo v = modelVideoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Video not found: " + id));
        return getFromMinio(v.getS3Key());
    }

    public InputStream getSliceContent(Integer id) {
        ModelSlice s = modelSliceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Slice not found: " + id));
        return getFromMinio(s.getS3Key());
    }

    public void deleteAllMediaForModel(String modelObjectKey) {
        modelPhotoRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)
                .forEach(p -> { removeFromMinio(p.getS3Key()); modelPhotoRepository.delete(p); });
        modelVideoRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)
                .forEach(v -> { removeFromMinio(v.getS3Key()); modelVideoRepository.delete(v); });
        modelSliceRepository.findByModelObjectKeyOrderByDisplayOrderAsc(modelObjectKey)
                .forEach(s -> { removeFromMinio(s.getS3Key()); modelSliceRepository.delete(s); });
    }

    private String getExtension(String fileName, String defaultExt) {
        if (fileName == null || !fileName.contains(".")) return "." + defaultExt;
        return fileName.substring(fileName.lastIndexOf('.'));
    }

    private void putToMinio(String s3Key, MultipartFile file) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(s3Key)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload media: " + e.getMessage(), e);
        }
    }

    private void removeFromMinio(String s3Key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(s3Key).build());
        } catch (Exception e) {
            log.warn("Failed to remove from MinIO: {}", s3Key, e);
        }
    }

    private InputStream getFromMinio(String s3Key) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(s3Key).build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get media: " + e.getMessage(), e);
        }
    }
}
