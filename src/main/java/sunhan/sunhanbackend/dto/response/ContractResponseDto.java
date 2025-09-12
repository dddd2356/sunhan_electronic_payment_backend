package sunhan.sunhanbackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import sunhan.sunhanbackend.enums.ContractStatus;
import sunhan.sunhanbackend.enums.ContractType;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class ContractResponseDto {
    private Long id;
    private String creatorId;
    private String employeeId;
    private ContractType contractType;
    private ContractStatus status;
    private String formDataJson;
    private String pdfUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean isDraft;
    private boolean isPrintable;

    // 반려 사유 필드 추가
    private String rejectionReason;

    // 추가 정보
    private String employeeName;
    private String creatorName;
    private String employeeDeptCode;
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
