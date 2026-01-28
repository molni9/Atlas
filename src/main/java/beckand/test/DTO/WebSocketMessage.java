package beckand.test.DTO;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ModelListRequest.class, name = "MODEL_LIST_REQUEST"),
    @JsonSubTypes.Type(value = ModelListResponse.class, name = "MODEL_LIST_RESPONSE"),
    @JsonSubTypes.Type(value = ErrorResponse.class, name = "ERROR"),
    @JsonSubTypes.Type(value = ConnectedMessage.class, name = "CONNECTED")
})
public abstract class WebSocketMessage {
    private String type;
}
