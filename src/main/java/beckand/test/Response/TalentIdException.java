package beckand.test.Response;

import lombok.Data;
import org.springframework.http.HttpStatus;
@Data
public class TalentIdException extends Exception {
    private HttpStatus status;
    private String exactMessage;

    public TalentIdException(HttpStatus status) {
        this.status = status;
    }

    public void setExactMessage(String message) {
        this.exactMessage = message;
    }

}
