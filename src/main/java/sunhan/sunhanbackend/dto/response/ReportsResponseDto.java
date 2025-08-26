package sunhan.sunhanbackend.dto.response;

import lombok.Data;
import sunhan.sunhanbackend.enums.ContractType;

import java.time.LocalDateTime;

@Data
public class ReportsResponseDto {
    private Long id;
    private String title;
    private String status;
    private ContractType type;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt; // 생성일 추가
    private String role; // CREATOR, EMPLOYEE, APPROVER 등 사용자 역할
    private String applicantName; // 신청자명 (휴가원의 경우)
    private String employeeName; // 직원명 (근로계약서의 경우)
    private String nextApprover; // 다음 승인자 정보
}