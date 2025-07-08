package sunhan.sunhanbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.request.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.auth.SignInResponseDto;
import sunhan.sunhanbackend.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/sign-in")
    public ResponseEntity<? super SignInResponseDto> signIn(@RequestBody @Valid SignInRequestDto requestBody){
        ResponseEntity<? super SignInResponseDto> response = authService.signIn(requestBody);
        return response;
    }
    // 웹(JWT) 로그아웃 엔드포인트
    @PostMapping("/logout/web")
    public ResponseEntity<String> logoutWeb(HttpServletResponse response) {
        // JWT 토큰을 저장하는 쿠키 삭제
        ResponseCookie cookie = ResponseCookie.from("accessToken", "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite("None")
                .secure(true)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());  // 쿠키 추가
        // 현재 인증된 사용자(userId) 조회
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok("로그아웃 완료");
    }

    // 토큰 유효성 검사 엔드포인트
    @GetMapping("/verify-token")
    public ResponseEntity<?> verifyToken(@RequestHeader("Authorization") String authorizationHeader) {
        // Authorization: Bearer <token>
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Authorization header is missing or invalid");
        }

        String token = authorizationHeader.substring(7); // "Bearer " 제외
        boolean isValid = authService.validateToken(token);

        if (isValid) {
            return ResponseEntity.ok("Token is valid");
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token is invalid or expired");
        }
    }
}
