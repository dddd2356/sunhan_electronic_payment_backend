package sunhan.sunhanbackend.common;

public interface ResponseCode {

    // 성공적인 작업을 나타내는 코드
    String SUCCESS = "SU";

    // 유효성 검사 실패를 나타내는 코드
    String VALIDATION_FAIL = "VF";

    // 로그인 실패를 나타내는 코드
    String SIGN_IN_FAIL = "SF";

    // 데이터베이스 오류를 나타내는 코드
    String DATABASE_ERROR = "DBE";

    // 로그아웃 실패를 나타내는 코드
    String LOGOUT_FAIL = "LF";
}
