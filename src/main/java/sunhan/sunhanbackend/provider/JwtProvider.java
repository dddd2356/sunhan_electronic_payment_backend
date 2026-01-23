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
import sunhan.sunhanbackend.enums.Role;

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

    // ⭐ 수정: role을 String 대신 Role enum으로 받기
    public String create(String userId, Role role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);

        Claims claims = Jwts.claims().setSubject(userId);
        // ⭐ Role enum의 name()을 저장 (USER, ADMIN 등)
        claims.put("role", role != null ? role.name() : Role.USER.name());
        claims.put("type", "access");

        log.info("✅ Creating access token for userId: {}, role: {}, expires at: {}",
                userId, role != null ? role.name() : "USER", expiryDate);

        return Jwts.builder()
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .compact();
    }

    // ⭐ 추가: 기존 String role 버전과의 호환성을 위한 오버로딩 (선택사항)
    public String create(String userId, String roleString) {
        Role role;
        try {
            // 숫자 문자열을 Role enum으로 변환 시도
            role = Role.fromValue(roleString);
        } catch (Exception e) {
            // 변환 실패 시 기본값 USER
            log.warn("⚠️ Invalid role string: {}, using USER", roleString);
            role = Role.USER;
        }
        return create(userId, role);
    }

    public String validate(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .setAllowedClockSkewSeconds(30)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String userId = claims.getSubject();
            String role = claims.get("role", String.class);
            log.info("✅ Token validated - userId: {}, role: {}", userId, role);

            return userId;
        } catch (ExpiredJwtException e) {
            log.error("❌ Access token expired: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("❌ Invalid access token: {}", e.getMessage());
            return null;
        }
    }

    // ⭐ 추가: Role 추출 메서드
    public Role extractRole(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .setAllowedClockSkewSeconds(30)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String roleString = claims.get("role", String.class);
            return Role.valueOf(roleString);
        } catch (Exception e) {
            log.error("❌ Failed to extract role: {}", e.getMessage());
            return Role.USER; // 기본값
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
            log.error("❌ Token expired: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("❌ Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public long getAccessTokenExpirationTime() {
        long expiresInSeconds = accessTokenExpiration / 1000;
        log.info("getAccessTokenExpirationTime: {} seconds", expiresInSeconds);
        return expiresInSeconds;
    }
}