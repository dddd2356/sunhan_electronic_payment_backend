package sunhan.sunhanbackend.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import sunhan.sunhanbackend.entity.CustomOAuth2User;
import sunhan.sunhanbackend.entity.UserEntity;
import sunhan.sunhanbackend.handler.OAuth2SuccessHandler;
import sunhan.sunhanbackend.respository.UserRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuth2UserServiceImplement extends DefaultOAuth2UserService {

    // 필요한 의존성 주입
    private final UserRepository userRepository;
    // OAuth2 인증 정보를 처리하는 메서드
    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {

        // 부모 클래스의 loadUser()를 호출하여 OAuth2User 객체를 가져옵니다.
        OAuth2User oAuth2User = super.loadUser(request);

        // 로그를 출력하여 인증된 사용자 정보를 확인합니다.
        try {
            System.out.println(new ObjectMapper().writeValueAsString(oAuth2User.getAttributes()));
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        // 사용자 정보를 담을 변수들 선언
        UserEntity userEntity = null;
        String userId = null;

        // 기존 유저 조회
        Optional<UserEntity> existingUser = userRepository.findById(userId);

        if (existingUser.isPresent()) {
            userEntity = existingUser.get();
        } else {
            // 새로운 사용자 생성
            userEntity = new UserEntity();
            userEntity.setUserId(userId);  // 로그인된 사용자 ID 저장
            userEntity.setPasswd(userId);  // 기본 패스워드
            userRepository.save(userEntity);  // 사용자 정보 저장
        }

        // CustomOAuth2User로 반환 (userId만 담아줌)
        return new CustomOAuth2User(userId);
    }
}