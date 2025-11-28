package sunhan.sunhanbackend.repository.mysql.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.position.Position;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    /**
     * 부서별 활성 직책 목록 조회 (표시 순서로 정렬)
     */
    List<Position> findByDeptCodeAndIsActiveTrueOrderByDisplayOrderAsc(String deptCode);

    /**
     * 부서별 모든 직책 목록 조회
     */
    List<Position> findByDeptCodeOrderByDisplayOrderAsc(String deptCode);

    /**
     * 부서 + 직책명으로 조회
     */
    Optional<Position> findByDeptCodeAndPositionNameAndIsActiveTrue(String deptCode, String positionName);

    /**
     * 특정 부서에서 가장 큰 displayOrder 값 조회
     */
    @Query("SELECT COALESCE(MAX(p.displayOrder), 0) FROM Position p WHERE p.deptCode = :deptCode")
    Integer findMaxDisplayOrderByDeptCode(String deptCode);

    /**
     * 부서별 직책 개수
     */
    long countByDeptCodeAndIsActiveTrue(String deptCode);
}
