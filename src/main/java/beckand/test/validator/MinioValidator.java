package beckand.test.validator;

import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;

@Component
public class MinioValidator {
    public void checkUploadFileToMinIO(BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            throw new IllegalArgumentException("Validation error: " + bindingResult.getAllErrors());
        }
    }
}
