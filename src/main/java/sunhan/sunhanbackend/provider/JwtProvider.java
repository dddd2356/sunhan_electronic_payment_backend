package sunhan.sunhanbackend.provider;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import io.jsonwebtoken.Claims;

@Slf4j
@Service
public class JwtProvider {

    @Value("${secret-key}")
    private String secretKey;

    @Value("${jwt.access-token.expiration:86400000}") // 24시간
    private Long accessTokenExpiration;
    private Key signingKey;
    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes());
    }
    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String create(String userId, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);

        Claims claims = Jwts.claims().setSubject(userId);
        claims.put("role", role);
        claims.put("type", "access");

        log.info("Creating access token for userId: {}, expires at: {}", userId, expiryDate);
        return Jwts.builder()
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(expiryDate)
                .compact();
    }

    public String validate(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .setAllowedClockSkewSeconds(30) // Consistent 30-second skew
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            log.error("Access token expired: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Invalid access token: {}", e.getMessage());
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .setAllowedClockSkewSeconds(30)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.error("Token expired: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Token validation failed: {}", e.getMessage());
            return false;
        }
    }
    public long getAccessTokenExpirationTime() {
        long expiresInSeconds = accessTokenExpiration / 1000;
        log.info("getAccessTokenExpirationTime: {} seconds", expiresInSeconds);
        return expiresInSeconds; // 3600초 반환
    }
}