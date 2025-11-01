package sunhan.sunhanbackend.repository.mysql.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentApprovalProcessRepository extends JpaRepository<DocumentApprovalProcess, Long> {

    /**
     * 문서 ID와 타입으로 프로세스 조회
     */
    Optional<DocumentApprovalProcess> findByDocumentIdAndDocumentType(
            Long documentId,
            DocumentType documentType
    );

    /**
     * 프로세스 ID로 이력 포함 조회
     */
    @Query("SELECT dap FROM DocumentApprovalProcess dap " +
            "LEFT JOIN FETCH dap.stepHistories " +
            "WHERE dap.id = :id")
    Optional<DocumentApprovalProcess> findByIdWithHistories(@Param("id") Long id);

    /**
     * 상태별 프로세스 조회
     */
    List<DocumentApprovalProcess> findByStatus(ApprovalProcessStatus status);

    /**
     * 현재 승인자가 처리해야 할 문서 조회
     */
    @Query("SELECT dap FROM DocumentApprovalProcess dap " +
            "JOIN dap.approvalLine al " +
            "JOIN al.steps step " +
            "WHERE step.stepOrder = dap.currentStepOrder " +
            "AND step.approverId = :approverId " +
            "AND dap.status = 'IN_PROGRESS'")
    List<DocumentApprovalProcess> findPendingProcessesByApproverId(@Param("approverId") String approverId);
}