package sunhan.sunhanbackend.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import sunhan.sunhanbackend.entity.CustomOAuth2User;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String userId = oAuth2User.getName();

        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();
            String role = user.getJobLevel(); // ✅ DB에서 role 가져오기
            String token = jwtProvider.create(userId, role); // ✅ 실제 role로 토큰 생성

            String redirectUrl = "http://localhost:3000/auth/oauth-response?" +
                    "token=" + token;
        }
    }
}