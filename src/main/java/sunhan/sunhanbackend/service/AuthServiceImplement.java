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
import sunhan.sunhanbackend.dto.request.auth.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.ResponseDto;
import sunhan.sunhanbackend.dto.response.auth.SignInResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.UserRepository;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImplement implements AuthService {

    private final UserRepository userRepository;
    private PasswordEncoder passwdEncoder = new BCryptPasswordEncoder();
    private final JwtProvider jwtProvider;

    @Override
    public ResponseEntity<? super SignInResponseDto> signIn(SignInRequestDto dto) {
        try {
            String userId = dto.getId();
            // userRepository.findByUserId()가 Optional을 반환하므로, orElse(null)을 사용해 UserEntity 또는 null을 받습니다.
            UserEntity userEntity = userRepository.findByUserId(userId).orElse(null);

            if (userEntity == null)
                return SignInResponseDto.signInFail();

            String passwd = dto.getPasswd();
            String storedPasswd = userEntity.getPasswd();

            boolean isMatched;
            // BCrypt 해시인지 평문인지 확인
            if (storedPasswd.startsWith("$2a$") || storedPasswd.startsWith("$2b$")) {
                // BCrypt 비교
                isMatched = passwdEncoder.matches(passwd, storedPasswd);
            } else {
                // 평문 비교 (기존 데이터 및 디폴트 비밀번호용)
                isMatched = passwd.equals(storedPasswd);
            }

            if (!isMatched)
                return SignInResponseDto.signInFail();

            String token = jwtProvider.create(userId, userEntity.getJobLevel());
            long expiresIn = jwtProvider.getAccessTokenExpirationTime();
            log.info("SignIn - userId: {}, expiresIn: {} seconds", userId, expiresIn);

            return SignInResponseDto.success(token, expiresIn);
        } catch (Exception exception) {
            log.error("SignIn error", exception);
            return ResponseDto.databaseError();
        }
    }

    @Override
    public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response, String loginMethod) {
        String token = null;
        Cookie[] cookies = request.getCookies();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        Cookie sessionCookie = new Cookie("Idea-b5e63b4f", null);
        sessionCookie.setMaxAge(0);
        sessionCookie.setDomain("localhost:9090");
        sessionCookie.setPath("/");
        sessionCookie.setSecure(false);
        sessionCookie.setHttpOnly(true);
        response.addCookie(sessionCookie);


        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(loginMethod + " 토큰이 없습니다.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("지원되지 않는 로그인 방식입니다.");
        }
    }

    @Override
    public boolean validateToken(String token) {
        return jwtProvider.validateToken(token);
    }

}

