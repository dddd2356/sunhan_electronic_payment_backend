package sunhan.sunhanbackend.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProfileRequestDto {
    private String phone;
    private String address;
    private String detailAddress;
    private String currentPassword; // 기존 비밀번호 (필수)
    private String newPassword;     // 새 비밀번호 (선택 사항)
    private Boolean privacyConsent;        // 개인정보 동의
    private Boolean notificationConsent;   // 알림 동의
    private String smsVerificationCode;  // 핸드폰 인증 코드
}