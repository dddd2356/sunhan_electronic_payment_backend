package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermissionEntity, Long> {
    // 특정 사용자가 특정 권한을 가지고 있는지 확인
    Optional<UserPermissionEntity> findByUserIdAndPermissionType(String userId, PermissionType permissionType);

    // 특정 사용자가 가진 모든 권한 조회
    List<UserPermissionEntity> findByUserId(String userId);
}
