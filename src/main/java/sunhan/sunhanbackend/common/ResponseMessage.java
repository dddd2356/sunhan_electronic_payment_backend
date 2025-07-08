package sunhan.sunhanbackend.common;

public interface ResponseMessage {

    // 성공적인 작업을 나타내는 메시지
    String SUCCESS = "Success.";

    // 유효성 검사 실패를 나타내는 메시지
    String VALIDATION_FAIL = "Validation failed.";

    // 로그인 실패를 나타내는 메시지
    String SIGN_IN_FAIL = "Login information mismatch.";

    // 데이터베이스 오류를 나타내는 메시지
    String DATABASE_ERROR = "Database Error.";

    // 로그아웃 실패를 나타내는 메시지
    String LOGOUT_FAIL = "Logout Fail.";
}
