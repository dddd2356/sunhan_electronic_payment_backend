package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeptPermissionRepository extends JpaRepository<DeptPermissionEntity, Long> {

    List<DeptPermissionEntity> findByDeptCode(String deptCode);

    List<DeptPermissionEntity> findByPermissionType(PermissionType permissionType);

    boolean existsByDeptCodeAndPermissionType(String deptCode, PermissionType permissionType);

    void deleteByDeptCodeAndPermissionType(String deptCode, PermissionType permissionType);

    @Query("SELECT DISTINCT dp.deptCode FROM DeptPermissionEntity dp WHERE dp.permissionType = :permissionType")
    List<String> findDeptCodesByPermissionType(@Param("permissionType") PermissionType permissionType);
}