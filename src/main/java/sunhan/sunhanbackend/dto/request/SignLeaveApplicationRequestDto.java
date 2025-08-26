package sunhan.sunhanbackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

// 휴가원 서명 요청을 위한 DTO
@Data
public class SignLeaveApplicationRequestDto {
    @NotBlank(message = "서명자의 ID는 필수입니다.")
    private String signerId; // 서명하는 사람의 ID (ex: applicantId, substituteId 등)

    @NotBlank(message = "서명 유형(역할)은 필수입니다. (예: applicant, substitute, deptHead)")
    private String signerType; // 서명하는 역할 (applicant, substitute, deptHead, hrStaff, etc.)

    @NotNull(message = "서명 정보는 필수입니다.")
    private SignatureEntry signatureEntry; // 서명 텍스트, 이미지 URL, 서명 여부

    @Data
    public static class SignatureEntry {
        private String text;
        private String imageUrl;
        private boolean isSigned;
        private LocalDateTime signatureDate;
    }
}