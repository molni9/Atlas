package beckand.test.Controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер для обработки .well-known запросов от браузеров
 * Эти запросы не критичны и могут игнорироваться
 */
@RestController
public class WellKnownController {

    @GetMapping("/.well-known/**")
    public ResponseEntity<Void> handleWellKnown() {
        // Возвращаем 204 No Content для .well-known запросов
        // Это нормально, браузеры просто проверяют наличие специфичных настроек
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
    
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> handleFavicon() {
        // Возвращаем 204 No Content для favicon запросов
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
