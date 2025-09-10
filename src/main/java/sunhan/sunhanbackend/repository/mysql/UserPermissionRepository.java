package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.List;
import java.util.Set;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermissionEntity, Long> {

    List<UserPermissionEntity> findByUserId(String userId);

    // 🔧 여러 사용자의 권한을 한번에 조회
    @Query("SELECT up FROM UserPermissionEntity up WHERE up.userId IN :userIds")
    List<UserPermissionEntity> findByUserIdIn(@Param("userIds") Set<String> userIds);

    List<UserPermissionEntity> findByPermissionType(PermissionType permissionType);

    boolean existsByUserIdAndPermissionType(String userId, PermissionType permissionType);

    void deleteByUserIdAndPermissionType(String userId, PermissionType permissionType);

    @Query("SELECT DISTINCT up.userId FROM UserPermissionEntity up WHERE up.permissionType = :permissionType")
    List<String> findUserIdsByPermissionType(@Param("permissionType") PermissionType permissionType);

    @Query("SELECT up FROM UserPermissionEntity up WHERE up.userId = :userId")
    Set<UserPermissionEntity> getAllUserPermissions(@Param("userId") String userId);
}