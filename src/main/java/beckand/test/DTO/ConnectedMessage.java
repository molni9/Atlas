package beckand.test.DTO;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConnectedMessage extends WebSocketMessage {
    public ConnectedMessage() {
        setType("CONNECTED");
    }
}
