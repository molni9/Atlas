package beckand.test.Controller;

import beckand.test.DTO.FileDTO;
import beckand.test.Service.CameraStateService;
import beckand.test.Service.FileService;
import beckand.test.Service.RenderService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

/**
 * Простейший MJPEG-стрим: бесконечно рендерит JPEG-кадры текущей модели
 * с учётом положения камеры из CameraStateService и отдаёт их как multipart/x-mixed-replace.
 *
 * Это можно использовать как источник для FFmpeg, который уже перекодирует поток в H.264/WebRTC.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/stream")
public class StreamController {

    private final FileService fileService;
    private final RenderService renderService;
    private final CameraStateService cameraStateService;

    @GetMapping(
            value = "/{objectKey}/mjpeg",
            produces = "multipart/x-mixed-replace;boundary=frame"
    )
    public StreamingResponseBody streamMjpeg(@PathVariable String objectKey, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return outputStream -> {
            log.info("Start MJPEG stream for {}", objectKey);
            // Загружаем модель один раз в память, рендерер сам использует кеш мешей
            FileDTO info = fileService.getFileInfo(objectKey);
            byte[] modelBytes;
            try (InputStream is = fileService.getFileContent(objectKey)) {
                modelBytes = is.readAllBytes();
            }

            final String boundary = "--frame\r\n";
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CameraStateService.Pose pose = cameraStateService.getPose(objectKey);
                    byte[] jpeg = renderService.renderModel(
                            objectKey,
                            modelBytes,
                            info.getFileType(),
                            pose.getAzimuth(),
                            pose.getElevation()
                    );

                    String headers = boundary +
                            "Content-Type: " + MediaType.IMAGE_JPEG_VALUE + "\r\n" +
                            "Content-Length: " + jpeg.length + "\r\n\r\n";
                    outputStream.write(headers.getBytes());
                    outputStream.write(jpeg);
                    outputStream.write("\r\n".getBytes());
                    outputStream.flush();

                    // ~30 fps
                    Thread.sleep(33);
                } catch (Exception e) {
                    log.info("Stop MJPEG stream for {}: {}", objectKey, e.getMessage());
                    break;
                }
            }
        };
    }
}


