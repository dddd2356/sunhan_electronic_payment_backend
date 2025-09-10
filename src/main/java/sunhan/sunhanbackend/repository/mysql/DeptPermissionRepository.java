package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface DeptPermissionRepository extends JpaRepository<DeptPermissionEntity, Long> {

    List<DeptPermissionEntity> findByDeptCode(String deptCode);

    List<DeptPermissionEntity> findByPermissionType(PermissionType permissionType);

    boolean existsByDeptCodeAndPermissionType(String deptCode, PermissionType permissionType);

    void deleteByDeptCodeAndPermissionType(String deptCode, PermissionType permissionType);

    @Query("SELECT DISTINCT dp.deptCode FROM DeptPermissionEntity dp WHERE dp.permissionType = :permissionType")
    List<String> findDeptCodesByPermissionType(@Param("permissionType") PermissionType permissionType);

    // 🆕 여러 부서의 권한을 한 번에 조회 (N+1 해결)
    @Query("SELECT dp FROM DeptPermissionEntity dp WHERE dp.deptCode IN :deptCodes")
    List<DeptPermissionEntity> findByDeptCodeIn(@Param("deptCodes") Set<String> deptCodes);

    // 🆕 여러 사용자의 부서 권한을 한 번에 조회
    @Query("SELECT dp FROM DeptPermissionEntity dp " +
            "JOIN UserEntity u ON u.deptCode = dp.deptCode " +
            "WHERE u.userId IN :userIds")
    List<DeptPermissionEntity> findDeptPermissionsByUserIds(@Param("userIds") List<String> userIds);
    @Query("SELECT dp FROM DeptPermissionEntity dp WHERE dp.deptCode = :deptCode")
    Set<DeptPermissionEntity> getAllDeptPermissions(@Param("deptCode") String deptCode);
}