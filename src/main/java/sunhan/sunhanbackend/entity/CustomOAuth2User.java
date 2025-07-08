package sunhan.sunhanbackend.entity;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
// OAuth2 인증 사용자 정보를 처리하는 클래스
public class CustomOAuth2User implements OAuth2User {

    private String userId;  // 사용자 ID

    @Override
    public Map<String, Object> getAttributes() {
        return null;  // OAuth2User에 필요한 추가 속성들을 구현할 수 있음
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return null;  // 사용자 권한 관련 정보를 구현할 수 있음
    }

    @Override
    public String getName() {
        return this.userId;  // 사용자 ID 반환
    }
}