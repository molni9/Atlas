package beckand.test.Service;

import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Хранит текущее положение "камеры" для каждой модели.
 * WebSocket управления обновляет состояние, а стриминг-рендер читает его.
 */
@Service
public class CameraStateService {

    private final ConcurrentMap<String, Pose> poses = new ConcurrentHashMap<>();

    public void updatePose(String objectKey, double azimuth, double elevation) {
        poses.put(objectKey, new Pose(azimuth, elevation));
    }

    public Pose getPose(String objectKey) {
        return poses.getOrDefault(objectKey, new Pose(0.0, 0.0));
    }

    @Value
    public static class Pose {
        double azimuth;
        double elevation;
    }
}


