package sunhan.sunhanbackend.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
// 로그인 요청 객체
public class SignInRequestDto {
    @NotBlank
    private String id; // 사용자 ID

    @NotBlank
    private String passwd; // 사용자 비밀번호
}

