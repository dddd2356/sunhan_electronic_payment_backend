package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermissionEntity, Long> {

    List<UserPermissionEntity> findByUserId(String userId);

    List<UserPermissionEntity> findByPermissionType(PermissionType permissionType);

    boolean existsByUserIdAndPermissionType(String userId, PermissionType permissionType);

    void deleteByUserIdAndPermissionType(String userId, PermissionType permissionType);

    @Query("SELECT DISTINCT up.userId FROM UserPermissionEntity up WHERE up.permissionType = :permissionType")
    List<String> findUserIdsByPermissionType(@Param("permissionType") PermissionType permissionType);
}