package sunhan.sunhanbackend.dto.response.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sunhan.sunhanbackend.common.ResponseCode;
import sunhan.sunhanbackend.common.ResponseMessage;
import sunhan.sunhanbackend.dto.response.ResponseDto;

@Getter
@AllArgsConstructor
public class SignInResponseDto extends ResponseDto{
    private String token;
    private long expiresIn;

    private SignInResponseDto(String code, String message, String token, long expiresIn) {
        super(code, message);
        this.token = token;
        this.expiresIn = expiresIn;
    }

    // 로그인 성공 응답
    // 수정된 메서드 (refreshToken을 필요로 하지 않는 경우)
    public static ResponseEntity<SignInResponseDto> success(String token, long expiresIn) {
        SignInResponseDto responseBody = new SignInResponseDto(ResponseCode.SUCCESS, ResponseMessage.SUCCESS, token, expiresIn);
        return ResponseEntity.status(HttpStatus.OK).body(responseBody);
    }


    // 로그인 실패 응답
    public static ResponseEntity<ResponseDto> signInFail() {
        ResponseDto responseBody = new ResponseDto(ResponseCode.SIGN_IN_FAIL, ResponseMessage.SIGN_IN_FAIL);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
    }
}