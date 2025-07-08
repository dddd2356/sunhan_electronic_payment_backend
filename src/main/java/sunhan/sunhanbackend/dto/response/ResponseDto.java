package sunhan.sunhanbackend.dto.response;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sunhan.sunhanbackend.common.ResponseCode;
import sunhan.sunhanbackend.common.ResponseMessage;

@Getter
@AllArgsConstructor
// 공통 응답 DTO
public class ResponseDto {

    private String code;    // 응답 코드
    private String message; // 응답 메시지

    // 기본 생성자 (기본 성공 응답)
    public ResponseDto(){
        this.code = ResponseCode.SUCCESS;
        this.message = ResponseMessage.SUCCESS;
    }

    // 데이터베이스 에러 응답
    public static ResponseEntity<ResponseDto> databaseError(){
        ResponseDto responseBody = new ResponseDto(ResponseCode.DATABASE_ERROR, ResponseMessage.DATABASE_ERROR);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
    }

    // 유효성 검사 실패 응답
    public static ResponseEntity<ResponseDto> validationFail(){
        ResponseDto responseBody = new ResponseDto(ResponseCode.VALIDATION_FAIL, ResponseMessage.VALIDATION_FAIL);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(responseBody);
    }
}

