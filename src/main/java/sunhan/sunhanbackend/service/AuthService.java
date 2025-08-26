package sunhan.sunhanbackend.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import sunhan.sunhanbackend.dto.request.auth.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.auth.SignInResponseDto;

public interface AuthService{
    // 로그인 처리
    ResponseEntity<? super SignInResponseDto> signIn(SignInRequestDto dto);
    // 로그아웃 처리
    ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response, String loginMethod);
    boolean validateToken(String token);
}