package beckand.test.Utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JwtUtils {
    private static final String SECRET_KEY = "your-secret-key";

    public UUID getUserId(String bearerToken) {
        String token = bearerToken.replace("Bearer ", "");
        Claims claims = Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .parseClaimsJws(token)
                .getBody();
        return UUID.fromString(claims.getSubject());
    }
}
