package sunhan.sunhanbackend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import sunhan.sunhanbackend.enums.ContractStatus;
import sunhan.sunhanbackend.enums.ContractType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // ✅ null 필드 제외
public class ContractResponseDto {
    private Long id;
    private String creatorId;
    private String employeeId;
    private ContractType contractType;
    private ContractStatus status;

    // ✅ formDataJson은 큰 데이터이므로 목록에서는 제외 가능
    private String formDataJson;

    private String pdfUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean isDraft;
    private boolean isPrintable;
    private String rejectionReason;

    // 추가 정보 (UserEntity에서 복사한 필드들)
    private String employeeName;
    private String creatorName;
    private String employeeDeptCode;

    // ✅ 부서명 추가 (deptCode 대신 이름 반환)
    private String employeeDeptName;

    private String employeeJobType;
    private String employeeJobLevel;
    private String employeePhone;
    private String employeeAddress;

    // 프론트엔드용 Status 생성자
    public ContractResponseDto(Long id, ContractStatus status,
                               LocalDateTime createdAt, LocalDateTime updatedAt,
                               ContractType contractType, boolean isPrintable) {
        this.id = id;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.contractType = contractType;
        this.isPrintable = isPrintable;
    }
}