package sunhan.sunhanbackend.dto.request.auth;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class UserRegistrationDto {
    private String usrId;       // 사원번호
    private String usrKorName;  // 이름
    private String deptCode;    // 부서코드
    private String jobType;     // 직종 (0: 정규직, 1: 계약직)
    private LocalDate startDate; // 입사일
}