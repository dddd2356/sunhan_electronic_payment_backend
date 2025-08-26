package sunhan.sunhanbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import sunhan.sunhanbackend.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtProvider jwtProvider;

    @PostMapping("/sign-in")
    public ResponseEntity<?> signIn(
            @RequestBody @Valid SignInRequestDto requestBody,
            HttpServletResponse response
    ) {
        // DTO 필드명이 id, passwd 이므로 getter도 getId(), getPasswd() 사용
        String userId   = requestBody.getId();
        String password = requestBody.getPasswd();

        // 1) 인증 (MySQL/Oracle 마이그레이션 포함)
        boolean ok = userService.authenticateUser(userId, password);
        if (!ok) {
            // 로그인 실패 시 DTO의 static 메서드 사용
            return SignInResponseDto.signInFail();
        }

        // 2) 로그인 성공 유저 정보 읽어오기
        UserEntity user = userService.getUserInfo(userId);

        // 3) JWT 토큰 생성 및 만료 시간 계산
        String token      = jwtProvider.create(user.getUserId(), user.getRoleForSecurity());
        long expiresInSec = jwtProvider.getAccessTokenExpirationTime(); // 예: 프로바이더에 정의된 만료(sec)

        // 4) HTTP Only 쿠키에 토큰 세팅
        ResponseCookie cookie = ResponseCookie.from("accessToken", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .sameSite("None")
                .maxAge(expiresInSec)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // 5) 성공 응답 DTO 생성
        return SignInResponseDto.success(token, expiresInSec);
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
    public ResponseEntity<String> verifyToken(@RequestHeader("Authorization") String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("Authorization header is missing or invalid");
        }
        String token = authorizationHeader.substring(7);
        boolean isValid = jwtProvider.validateToken(token);

        if (isValid) {
            return ResponseEntity.ok("Token is valid");
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token is invalid or expired");
        }
    }
}
