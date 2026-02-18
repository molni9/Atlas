package beckand.test.Controller;

import beckand.test.DTO.MediaItemDto;
import beckand.test.DTO.ModelMetaResponse;
import beckand.test.DTO.ModelMetaUpdateRequest;
import beckand.test.DTO.SliceItemDto;
import beckand.test.Service.ModelMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
@Tag(name = "Model Meta", description = "Описание модели, фото, видео, срезы")
public class ModelMetaController {

    private final ModelMediaService modelMediaService;

    @Operation(summary = "Метаданные модели", description = "Описание, список фото, видео и срезов модели")
    @GetMapping("/{objectKey:.+}/meta")
    public ResponseEntity<ModelMetaResponse> getModelMeta(
            @Parameter(description = "Ключ модели (s3ObjectKey)", required = true) @PathVariable("objectKey") String objectKey,
            HttpServletRequest request
    ) {
        ModelMetaResponse meta = modelMediaService.getMeta(objectKey);
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request).replacePath(null).build().toUriString();
        String encodedKey = java.net.URLEncoder.encode(objectKey, StandardCharsets.UTF_8).replace("+", "%20");
        for (MediaItemDto p : meta.getPhotos()) {
            p.setUrl(baseUrl + "/files/" + encodedKey + "/media/photo/" + p.getId());
        }
        for (MediaItemDto v : meta.getVideos()) {
            v.setUrl(baseUrl + "/files/" + encodedKey + "/media/video/" + v.getId());
        }
        for (SliceItemDto s : meta.getSlices()) {
            s.setUrl(baseUrl + "/files/" + encodedKey + "/media/slice/" + s.getId());
        }
        return ResponseEntity.ok(meta);
    }

    @Operation(summary = "Обновить описание модели")
    @PatchMapping("/{objectKey:.+}/meta")
    public ResponseEntity<Void> updateModelMeta(
            @Parameter(description = "Ключ модели", required = true) @PathVariable("objectKey") String objectKey,
            @RequestBody ModelMetaUpdateRequest body
    ) {
        modelMediaService.updateDescription(objectKey, body.getDescription());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Загрузить фото к модели")
    @PostMapping(value = "/{objectKey:.+}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaItemDto> uploadPhoto(
            @Parameter(description = "Ключ модели", required = true) @PathVariable("objectKey") String objectKey,
            @RequestParam("file") MultipartFile file
    ) {
        MediaItemDto dto = modelMediaService.uploadPhoto(objectKey, file);
        return ResponseEntity.created(URI.create("/files/" + objectKey + "/media/photo/" + dto.getId())).body(dto);
    }

    @Operation(summary = "Удалить фото")
    @DeleteMapping("/{objectKey:.+}/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("photoId") Integer photoId
    ) {
        modelMediaService.deletePhoto(photoId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Загрузить видео к модели")
    @PostMapping(value = "/{objectKey:.+}/videos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaItemDto> uploadVideo(
            @Parameter(description = "Ключ модели", required = true) @PathVariable("objectKey") String objectKey,
            @RequestParam("file") MultipartFile file
    ) {
        MediaItemDto dto = modelMediaService.uploadVideo(objectKey, file);
        return ResponseEntity.created(URI.create("/files/" + objectKey + "/media/video/" + dto.getId())).body(dto);
    }

    @Operation(summary = "Удалить видео")
    @DeleteMapping("/{objectKey:.+}/videos/{videoId}")
    public ResponseEntity<Void> deleteVideo(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("videoId") Integer videoId
    ) {
        modelMediaService.deleteVideo(videoId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Загрузить срез модели")
    @PostMapping(value = "/{objectKey:.+}/slices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SliceItemDto> uploadSlice(
            @Parameter(description = "Ключ модели", required = true) @PathVariable("objectKey") String objectKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "z") String axis,
            @RequestParam(required = false, defaultValue = "0") Integer sliceIndex
    ) {
        SliceItemDto dto = modelMediaService.uploadSlice(objectKey, file, axis, sliceIndex);
        return ResponseEntity.created(URI.create("/files/" + objectKey + "/media/slice/" + dto.getId())).body(dto);
    }

    @Operation(summary = "Удалить срез")
    @DeleteMapping("/{objectKey:.+}/slices/{sliceId}")
    public ResponseEntity<Void> deleteSlice(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("sliceId") Integer sliceId
    ) {
        modelMediaService.deleteSlice(sliceId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Получить изображение фото")
    @GetMapping("/{objectKey:.+}/media/photo/{photoId}")
    public ResponseEntity<byte[]> getPhoto(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("photoId") Integer photoId
    ) {
        try (InputStream is = modelMediaService.getPhotoContent(photoId)) {
            byte[] bytes = is.readAllBytes();
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get photo: " + e.getMessage(), e);
        }
    }

    @Operation(summary = "Получить видео")
    @GetMapping("/{objectKey:.+}/media/video/{videoId}")
    public ResponseEntity<byte[]> getVideo(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("videoId") Integer videoId
    ) {
        try (InputStream is = modelMediaService.getVideoContent(videoId)) {
            byte[] bytes = is.readAllBytes();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get video: " + e.getMessage(), e);
        }
    }

    @Operation(summary = "Получить изображение среза")
    @GetMapping("/{objectKey:.+}/media/slice/{sliceId}")
    public ResponseEntity<byte[]> getSlice(
            @PathVariable("objectKey") String objectKey,
            @PathVariable("sliceId") Integer sliceId
    ) {
        try (InputStream is = modelMediaService.getSliceContent(sliceId)) {
            byte[] bytes = is.readAllBytes();
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get slice: " + e.getMessage(), e);
        }
    }
}
