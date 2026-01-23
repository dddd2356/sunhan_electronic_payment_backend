package sunhan.sunhanbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.request.auth.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.auth.SignInResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.service.AuthService;
import sunhan.sunhanbackend.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    @PostMapping("/sign-in")
    public ResponseEntity<?> signIn(
            @RequestBody @Valid SignInRequestDto requestBody,
            HttpServletResponse response
    ) {
        log.info("🔐 Login attempt for userId: {}", requestBody.getId());

        // 1. 로그인 처리
        ResponseEntity<? super SignInResponseDto> result = authService.signIn(requestBody);

        // 2. 로그인 실패 시
        if (result.getStatusCode() != HttpStatus.OK) {
            log.warn("❌ Login failed for userId: {}", requestBody.getId());
            return result;
        }

        // 3. 로그인 성공 시 쿠키 설정
        SignInResponseDto signInResponse = (SignInResponseDto) result.getBody();
        if (signInResponse != null) {
            String token = signInResponse.getToken();
            long expiresIn = signInResponse.getExpiresIn();

            // ⭐ 수정된 쿠키 설정 (localhost 대응)
            ResponseCookie cookie = ResponseCookie.from("accessToken", token)
                    .httpOnly(true)
                    .secure(false)     // ⭐ localhost는 HTTP이므로 false
                    .path("/")
                    .sameSite("Lax")   // ⭐ Lax로 변경 (None은 HTTPS 필수)
                    .maxAge(expiresIn)
                    .build();

            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            log.info("✅ Cookie set - secure: false, sameSite: Lax, maxAge: {}", expiresIn);
        }

        return result;
    }

    @PostMapping("/logout/web")
    public ResponseEntity<String> logoutWeb(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            String userId = SecurityContextHolder.getContext()
                    .getAuthentication()
                    .getName();
            log.info("👋 Logout request from userId: {}", userId);

            // ⭐ 쿠키 삭제 (로그인과 동일한 설정)
            ResponseCookie deleteCookie = ResponseCookie.from("accessToken", "")
                    .httpOnly(true)
                    .secure(false)   // ⭐ 로그인과 동일하게
                    .path("/")
                    .sameSite("Lax") // ⭐ 로그인과 동일하게
                    .maxAge(0)       // 즉시 삭제
                    .build();

            response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie.toString());
            log.info("✅ Cookie deleted");

            return ResponseEntity.ok("로그아웃 완료");

        } catch (Exception e) {
            log.error("❌ Logout error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("로그아웃 처리 중 오류 발생");
        }
    }

    @GetMapping("/verify-token")
    public ResponseEntity<String> verifyToken(
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body("Authorization header is missing or invalid");
        }

        String token = authorizationHeader.substring(7);
        boolean isValid = authService.validateToken(token);

        if (isValid) {
            log.info("✅ Token validation successful");
            return ResponseEntity.ok("Token is valid");
        } else {
            log.warn("❌ Token validation failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Token is invalid or expired");
        }
    }
}
