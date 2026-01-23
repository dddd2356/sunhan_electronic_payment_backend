package sunhan.sunhanbackend.dto.response.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import sunhan.sunhanbackend.common.ResponseCode;
import sunhan.sunhanbackend.common.ResponseMessage;
import sunhan.sunhanbackend.dto.request.auth.SignInRequestDto;
import sunhan.sunhanbackend.dto.response.ResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignInResponseDto extends ResponseDto {
    private String token;
    private long expiresIn;

    // ⭐ ResponseDto의 필드를 포함하는 생성자
    public SignInResponseDto(String code, String message, String token, long expiresIn) {
        super(code, message);
        this.token = token;
        this.expiresIn = expiresIn;
    }

    // 로그인 성공 응답
    public static ResponseEntity<SignInResponseDto> success(String token, long expiresIn) {
        SignInResponseDto responseBody = new SignInResponseDto(
                ResponseCode.SUCCESS,
                ResponseMessage.SUCCESS,
                token,
                expiresIn
        );
        return ResponseEntity.status(HttpStatus.OK).body(responseBody);
    }

    // 로그인 실패 응답
    public static ResponseEntity<ResponseDto> signInFail() {
        ResponseDto responseBody = new ResponseDto(
                ResponseCode.SIGN_IN_FAIL,
                ResponseMessage.SIGN_IN_FAIL
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
    }
}