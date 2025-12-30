package sunhan.sunhanbackend.repository.mysql.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStepHistory;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalStepHistoryRepository extends JpaRepository<ApprovalStepHistory, Long> {

    /**
     * 프로세스 ID로 이력 조회 (순서대로)
     */
    List<ApprovalStepHistory> findByApprovalProcessIdOrderByStepOrderAsc(Long approvalProcessId);

    /**
     * 프로세스 ID와 단계로 이력 조회
     */
    List<ApprovalStepHistory> findByApprovalProcessIdAndStepOrder(
            Long approvalProcessId,
            Integer stepOrder
    );

    /**
     * 승인자별 이력 조회
     */
    List<ApprovalStepHistory> findByApproverId(String approverId);

    /**
     * 특정 결재 프로세스 ID, 단계 순서, 액션(PENDING/APPROVED/REJECTED)을 기준으로 이력을 조회합니다.
     */
    Optional<ApprovalStepHistory> findByApprovalProcessIdAndStepOrderAndAction(
            Long approvalProcessId,
            Integer stepOrder,
            ApprovalAction action
    );
    // 승인자 ID로 이미 승인된 이력 찾기
    Optional<ApprovalStepHistory> findByApprovalProcessIdAndApproverIdAndAction(
            Long approvalProcessId,
            String approverId,
            ApprovalAction action
    );

    List<ApprovalStepHistory> findByApprovalProcessId(Long approvalProcessId);

    @Query("SELECT h FROM ApprovalStepHistory h " +
            "WHERE h.approvalProcess.id = :approvalProcessId " +
            "AND h.stepOrder = :stepOrder " +
            "AND h.action IN :actions " +
            "ORDER BY h.actionDate DESC")  // 최신 actionDate 우선 정렬
    List<ApprovalStepHistory> findByApprovalProcessIdAndStepOrderAndActionIn(
            @Param("approvalProcessId") Long approvalProcessId,
            @Param("stepOrder") Integer stepOrder,
            @Param("actions") List<ApprovalAction> actions
    );
}