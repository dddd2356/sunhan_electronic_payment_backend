package sunhan.sunhanbackend.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.request.auth.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.ResponseDto;
import sunhan.sunhanbackend.dto.response.auth.SignInResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.UserRepository;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImplement implements AuthService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwdEncoder;
    private final JwtProvider jwtProvider;

    @Override
    @Transactional
    public ResponseEntity<? super SignInResponseDto> signIn(SignInRequestDto dto) {
        try {
            String userId = dto.getId();
            String passwd = dto.getPasswd();

            // ✅ UserService.authenticateUser()가 모든 인증 로직 처리
            boolean authenticated = userService.authenticateUser(userId, passwd);

            if (!authenticated) {
                log.warn("❌ Authentication failed for userId: {}", userId);
                return SignInResponseDto.signInFail();
            }

            // ✅ 캐시 우회 조회 (재시도 제거)
            UserEntity userEntity = userRepository.findByUserIdNoCache(userId)
                    .orElseThrow(() -> new RuntimeException("User not found after authentication: " + userId));

            // ✅ 토큰 생성
            String token = jwtProvider.create(userId, userEntity.getJobLevel());
            long expiresIn = jwtProvider.getAccessTokenExpirationTime();

            log.info("✅ 로그인 성공 - userId: {}, jobLevel: {}, expiresIn: {} seconds",
                    userId, userEntity.getJobLevel(), expiresIn);

            return SignInResponseDto.success(token, expiresIn);

        } catch (Exception exception) {
            log.error("❌ SignIn error", exception);
            return ResponseDto.databaseError();
        }
    }

    @Override
    public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response, String loginMethod) {
        try {
            // 세션 무효화
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
                log.info("✅ Session invalidated");
            }

            // 쿠키 삭제
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if ("accessToken".equals(cookie.getName())) {
                        Cookie deleteCookie = new Cookie("accessToken", null);
                        deleteCookie.setMaxAge(0);
                        deleteCookie.setPath("/");
                        deleteCookie.setHttpOnly(true);
                        deleteCookie.setSecure(true);
                        response.addCookie(deleteCookie);
                        log.info("✅ AccessToken cookie deleted");
                    }
                }
            }

            return ResponseEntity.ok("로그아웃 완료");

        } catch (Exception e) {
            log.error("❌ Logout error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("로그아웃 처리 중 오류 발생");
        }
    }

    @Override
    public boolean validateToken(String token) {
        return jwtProvider.validateToken(token);
    }

}

