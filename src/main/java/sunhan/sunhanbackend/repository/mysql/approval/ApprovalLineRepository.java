package sunhan.sunhanbackend.repository.mysql.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalLineRepository extends JpaRepository<ApprovalLine, Long> {

    /**
     * 문서 타입별 활성화된 결재라인 조회
     */
    List<ApprovalLine> findByDocumentTypeAndIsActiveTrue(DocumentType documentType);

    /**
     * 생성자별 결재라인 조회
     */
    List<ApprovalLine> findByCreatedByAndIsActiveTrue(String createdBy);

    /**
     * 결재라인 ID로 단계 포함 조회
     */
    @Query("SELECT al FROM ApprovalLine al LEFT JOIN FETCH al.steps WHERE al.id = :id")
    Optional<ApprovalLine> findByIdWithSteps(@Param("id") Long id);

    // 문서 타입별 활성화된 결재라인 조회 (삭제되지 않은 것만)
    List<ApprovalLine> findByDocumentTypeAndIsActiveTrueAndIsDeletedFalse(DocumentType documentType);
    @Query("SELECT DISTINCT al FROM ApprovalLine al " +
            "LEFT JOIN FETCH al.steps " +
            "WHERE al.documentType = :documentType " +
            "AND al.isActive = true " +
            "AND al.isDeleted = false " +
            "ORDER BY al.createdAt DESC")
    List<ApprovalLine> findByDocumentTypeAndIsActiveTrueAndIsDeletedFalseWithSteps(
            @Param("documentType") DocumentType documentType
    );
    // 생성자별 결재라인 조회 (삭제되지 않은 것만)
    @Query("SELECT al FROM ApprovalLine al LEFT JOIN FETCH al.steps s " +
            "WHERE al.documentType = :documentType AND al.createdBy = :createdBy AND al.isDeleted = false " +
            "ORDER BY al.isActive DESC, al.createdAt DESC, s.stepOrder")
    List<ApprovalLine> findByDocumentTypeAndCreatedByAndIsActiveTrueWithSteps(
            @Param("documentType") DocumentType documentType,
            @Param("createdBy") String createdBy
    );

    // documentType 없이 전체 조회 (삭제되지 않은 것만)
    @Query("SELECT al FROM ApprovalLine al LEFT JOIN FETCH al.steps s " +
            "WHERE al.createdBy = :createdBy AND al.isDeleted = false " +
            "ORDER BY al.isActive DESC, al.createdAt DESC, s.stepOrder")
    List<ApprovalLine> findByCreatedByAndIsActiveTrueWithSteps(
            @Param("createdBy") String createdBy
    );
}