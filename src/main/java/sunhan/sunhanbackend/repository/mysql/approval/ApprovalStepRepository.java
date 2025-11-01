package sunhan.sunhanbackend.repository.mysql.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;

import java.util.List;

@Repository
public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {

    /**
     * 결재라인 ID로 단계 조회 (순서대로)
     */
    List<ApprovalStep> findByApprovalLineIdOrderByStepOrderAsc(Long approvalLineId);

    /**
     * 결재라인 ID로 모든 단계 삭제
     */
    void deleteByApprovalLineId(Long approvalLineId);
}
