package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;

import java.util.Optional;

@Repository
public interface DeptPermissionRepository extends JpaRepository<DeptPermissionEntity, Long> {
    // 특정 부서가 특정 권한을 가지고 있는지 확인
    Optional<DeptPermissionEntity> findByDeptCodeAndPermissionType(String deptCode, PermissionType permissionType);
}
