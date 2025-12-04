package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import sunhan.sunhanbackend.entity.mysql.Department;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, String> {

    @Query("SELECT d FROM Department d WHERE d.useFlag = '1'")
    List<Department> findAllActive();
}