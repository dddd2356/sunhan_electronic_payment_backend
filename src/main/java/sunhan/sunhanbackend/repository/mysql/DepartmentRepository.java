package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sunhan.sunhanbackend.entity.mysql.Department;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, String> {

    @Query("SELECT d FROM Department d WHERE d.useFlag = '1'")
    List<Department> findAllActive();

    // base 코드로 그룹화된 부서 조회 (LIKE 패턴 사용)
    @Query("SELECT d FROM Department d WHERE d.deptCode LIKE :baseCode% AND d.useFlag = '1'")
    List<Department> findByBaseDeptCode(@Param("baseCode") String baseCode);
}