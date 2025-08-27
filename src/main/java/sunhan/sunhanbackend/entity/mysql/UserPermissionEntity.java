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
@Table(name = "user_permissions")
public class UserPermissionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_type", nullable = false)
    private PermissionType permissionType;

    public UserPermissionEntity(String userId, PermissionType permissionType) {
        this.userId = userId;
        this.permissionType = permissionType;
    }
}