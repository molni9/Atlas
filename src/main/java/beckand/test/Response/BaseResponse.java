package beckand.test.Response;

import lombok.Data;

@Data
public class BaseResponse<T> {
    private boolean success;
    private T body;
    private String message;

    public BaseResponse<T> ok() {
        this.success = true;
        return this;
    }

    public BaseResponse<T> fail() {
        this.success = false;
        return this;
    }

    public BaseResponse<T> body(T body) {
        this.body = body;
        return this;
    }

    public BaseResponse<T> message(String message) {
        this.message = message;
        return this;
    }
}
