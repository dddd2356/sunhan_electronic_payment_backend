package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.PermissionType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dept_permissions")
public class DeptPermissionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dept_code", nullable = false)
    private String deptCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_type", nullable = false)
    private PermissionType permissionType;

    public DeptPermissionEntity(String deptCode, PermissionType permissionType) {
        this.deptCode = deptCode;
        this.permissionType = permissionType;
    }
}