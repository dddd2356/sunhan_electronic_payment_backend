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
}
