package beckand.test.DTO;

import beckand.test.DTO.FileDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class ModelListResponse extends WebSocketMessage {
    private String requestId;
    private List<FileDTO> models;
}
