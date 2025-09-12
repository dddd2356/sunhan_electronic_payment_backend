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
}