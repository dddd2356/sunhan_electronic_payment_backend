package sunhan.sunhanbackend.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UserResponseDto {
    private String userId;
    private String userName;
    private String deptCode;
    private String jobType;
    private String jobLevel;
    private String phone;
    private String address;
    private String detailAddress;
    private String role;
    private List<String> permissions; // UserPermissionEntity + DeptPermissionEntity
    private byte[] signimage;
    private Boolean privacyConsent;
    private Boolean notificationConsent;
    private String useFlag;
    // 휴가 관련 필드 (서비스에서 setTotalVacationDays / setUsedVacationDays 호출함)
    private Integer totalVacationDays;
    private Integer usedVacationDays;
    private Integer remainingVacationDays;

    // 관리 관련 플래그 / 통계
    private Boolean isAdmin;
    private Boolean isSuperAdmin;
    private Integer manageableUsersCount;
}
