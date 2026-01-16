package sunhan.sunhanbackend.repository.mysql.consent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentAgreement;
import sunhan.sunhanbackend.enums.consent.ConsentStatus;
import sunhan.sunhanbackend.enums.consent.ConsentType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentAgreementRepository extends JpaRepository<ConsentAgreement, Long> {

// ==================== 중복 방지 ====================

    /**
     * 특정 사용자가 특정 타입의 완료된 동의서를 가지고 있는지 확인
     * - 동일 타입 동의서 재발급 방지용
     */
    boolean existsByTargetUserIdAndConsentFormTypeAndStatus(
            String targetUserId,
            ConsentType type,
            ConsentStatus status
    );

    /**
     * 사용자의 특정 타입 완료 동의서 조회
     */
    Optional<ConsentAgreement> findByTargetUserIdAndConsentFormTypeAndStatus(
            String targetUserId,
            ConsentType type,
            ConsentStatus status
    );

    // ==================== 목록 조회 (권한별) ====================
    /*
     * 대상자용: 나에게 온 동의서 목록 (작성 대기 중)
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.targetUserId = :userId AND ca.status = :status")
    List<ConsentAgreement> findByTargetUserIdAndStatus(
            @Param("userId") String targetUserId,
            @Param("status") ConsentStatus status
    );

    /**
     * 대상자용: 내가 작성한 모든 동의서
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.targetUserId = :userId")
    List<ConsentAgreement> findByTargetUserId(@Param("userId") String targetUserId);

    /**
     * 생성자용: 내가 발송한 동의서 목록
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.creatorId = :creatorId")
    List<ConsentAgreement> findByCreatorId(@Param("creatorId") String creatorId);

    /**
     * 생성자용: 내가 발송한 완료 동의서만
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.creatorId = :creatorId AND ca.status = :status")
    List<ConsentAgreement> findByCreatorIdAndStatus(
            @Param("creatorId") String creatorId,
            @Param("status") ConsentStatus status
    );

    // ==================== 관리자용 검색/필터링 ====================

    /**
     * 상태별 필터링
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.status = :status")
    List<ConsentAgreement> findByStatus(@Param("status") ConsentStatus status);

    /**
     * 상태 + 대상자 필터링
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.status = :status AND ca.targetUserId = :userId")
    List<ConsentAgreement> findByStatusAndTargetUserId(
            @Param("status") ConsentStatus status,
            @Param("userId") String targetUserId
    );
    /**
     * 타입별 필터링
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.consentForm.type = :type")
    List<ConsentAgreement> findByType(@Param("type") ConsentType type);

    /**
     * 복합 검색 (관리자용)
     * - 상태, 타입, 사용자 ID, 사용자명으로 검색
     */
    @Query("""
        SELECT ca FROM ConsentAgreement ca
        WHERE (:status IS NULL OR ca.status = :status)
        AND (:type IS NULL OR ca.consentForm.type = :type)
        AND (:searchTerm IS NULL OR 
             ca.targetUserId LIKE %:searchTerm% OR 
             ca.targetUserName LIKE %:searchTerm% OR
             ca.creatorId LIKE %:searchTerm%)
        ORDER BY ca.createdAt DESC
    """)
    Page<ConsentAgreement> searchAgreements(
            @Param("status") ConsentStatus status,
            @Param("type") ConsentType type,
            @Param("searchTerm") String searchTerm,
            Pageable pageable
    );

    /**
     * 전체 목록 (최신순 정렬)
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm ORDER BY ca.createdAt DESC")
    List<ConsentAgreement> findAllByOrderByCreatedAtDesc();

    // ==================== 통계 쿼리 ====================

    /**
     * 완료된 동의서 수 (전체)
     */
    long countByStatus(ConsentStatus status);

    /**
     * 타입별 완료 수
     */
    @Query("SELECT COUNT(ca) FROM ConsentAgreement ca WHERE ca.consentForm.type = :type AND ca.status = :status")
    long countByTypeAndStatus(@Param("type") ConsentType type, @Param("status") ConsentStatus status);

    /**
     * 특정 사용자의 완료 동의서 수
     */
    long countByTargetUserIdAndStatus(String targetUserId, ConsentStatus status);

    /**
     * 생성자별 발송 수
     */
    long countByCreatorId(String creatorId);

    // ==================== 조회 권한 체크용 ====================

    /**
     * ID로 조회 (Fetch Join으로 ConsentForm도 함께 로드)
     */
    @Query("SELECT ca FROM ConsentAgreement ca JOIN FETCH ca.consentForm WHERE ca.id = :id")
    Optional<ConsentAgreement> findByIdWithForm(@Param("id") Long id);

    /**
     * 사용자 접근 가능 여부 확인
     * - 대상자 본인 OR 생성자 (완료 상태만) OR 관리 권한 보유자
     */
    @Query("""
        SELECT CASE WHEN COUNT(ca) > 0 THEN true ELSE false END
        FROM ConsentAgreement ca
        WHERE ca.id = :agreementId
        AND (ca.targetUserId = :userId 
             OR (ca.creatorId = :userId AND ca.status = 'COMPLETED'))
    """)
    boolean canUserAccessAgreement(@Param("agreementId") Long agreementId, @Param("userId") String userId);
}