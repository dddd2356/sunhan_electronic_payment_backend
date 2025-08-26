package sunhan.sunhanbackend.repository.mysql;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReportsRepositoryImpl implements ReportsRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String FIND_DRAFT_DOCUMENTS_QUERY = """
        SELECT
            'CONTRACT' as document_type,
            ec.id,
            ec.created_at,
            ec.updated_at,
            '근로계약서' as title,
            ec.status,
            creator.name as creator_name,
            employee.name as employee_name
        FROM employment_contract ec
        JOIN usrmst creator ON ec.creator_id = creator.id
        JOIN usrmst employee ON ec.employee_id = employee.id
        WHERE ec.creator_id = :userId
          AND ec.status = 'DRAFT'
        
        UNION ALL
        
        SELECT
            'LEAVE_APPLICATION' as document_type,
            la.id,
            la.created_at,
            la.updated_at,
            '휴가원' as title,
            la.status,
            applicant.name as creator_name,
            applicant.name as employee_name
        FROM leave_application la
        JOIN usrmst applicant ON la.applicant_id = applicant.id
        WHERE la.applicant_id = :userId
          AND la.status = 'DRAFT'
        
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
    """;

    private static final String COUNT_DRAFT_DOCUMENTS_QUERY = """
        SELECT COUNT(*) FROM (
            SELECT ec.id
            FROM employment_contract ec
            WHERE ec.creator_id = :userId AND ec.status = 'DRAFT'
            
            UNION ALL
            
            SELECT la.id
            FROM leave_application la
            WHERE la.applicant_id = :userId AND la.status = 'DRAFT'
        ) combined_docs
    """;

    private static final String FIND_IN_PROGRESS_DOCUMENTS_QUERY = """
        SELECT
            'CONTRACT' as document_type,
            ec.id,
            ec.created_at,
            ec.updated_at,
            '근로계약서' as title,
            ec.status,
            creator.name as creator_name,
            employee.name as employee_name
        FROM employment_contract ec
        JOIN usrmst creator ON ec.creator_id = creator.id
        JOIN usrmst employee ON ec.employee_id = employee.id
        WHERE ec.creator_id = :userId
          AND ec.status IN ('SENT_TO_EMPLOYEE', 'SIGNED_BY_EMPLOYEE')
        
        UNION ALL
        
        SELECT
            'LEAVE_APPLICATION' as document_type,
            la.id,
            la.created_at,
            la.updated_at,
            '휴가원' as title,
            la.status,
            applicant.name as creator_name,
            applicant.name as employee_name
        FROM leave_application la
        JOIN usrmst applicant ON la.applicant_id = applicant.id
        WHERE la.applicant_id = :userId
          AND la.status IN ('PENDING_SUBSTITUTE', 'PENDING_DEPT_HEAD', 'PENDING_CENTER_DIRECTOR',
                           'PENDING_ADMIN_DIRECTOR', 'PENDING_CEO_DIRECTOR', 'PENDING_HR_STAFF')
        
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
    """;

    private static final String COUNT_IN_PROGRESS_DOCUMENTS_QUERY = """
        SELECT COUNT(*) FROM (
            SELECT ec.id
            FROM employment_contract ec
            WHERE ec.creator_id = :userId
              AND ec.status IN ('SENT_TO_EMPLOYEE', 'SIGNED_BY_EMPLOYEE')
            
            UNION ALL
            
            SELECT la.id
            FROM leave_application la
            WHERE la.applicant_id = :userId
              AND la.status IN ('PENDING_SUBSTITUTE', 'PENDING_DEPT_HEAD', 'PENDING_CENTER_DIRECTOR',
                               'PENDING_ADMIN_DIRECTOR', 'PENDING_CEO_DIRECTOR', 'PENDING_HR_STAFF')
        ) combined_docs
    """;

    private static final String FIND_REJECTED_DOCUMENTS_QUERY = """
        SELECT
            'CONTRACT' as document_type,
            ec.id,
            ec.created_at,
            ec.updated_at,
            '근로계약서' as title,
            ec.status,
            creator.name as creator_name,
            employee.name as employee_name
        FROM employment_contract ec
        JOIN usrmst creator ON ec.creator_id = creator.id
        JOIN usrmst employee ON ec.employee_id = employee.id
        WHERE (ec.creator_id = :userId OR ec.employee_id = :userId)
          AND ec.status IN ('RETURNED_TO_ADMIN', 'DELETED')
        
        UNION ALL
        
        SELECT
            'LEAVE_APPLICATION' as document_type,
            la.id,
            la.created_at,
            la.updated_at,
            '휴가원' as title,
            la.status,
            applicant.name as creator_name,
            applicant.name as employee_name
        FROM leave_application la
        JOIN usrmst applicant ON la.applicant_id = applicant.id
        WHERE la.applicant_id = :userId
          AND la.status = 'REJECTED'
        
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
    """;

    private static final String COUNT_REJECTED_DOCUMENTS_QUERY = """
        SELECT COUNT(*) FROM (
            SELECT ec.id
            FROM employment_contract ec
            WHERE (ec.creator_id = :userId OR ec.employee_id = :userId)
              AND ec.status IN ('RETURNED_TO_ADMIN', 'DELETED')
            
            UNION ALL
            
            SELECT la.id
            FROM leave_application la
            WHERE la.applicant_id = :userId
              AND la.status = 'REJECTED'
        ) combined_docs
    """;

    private static final String FIND_COMPLETED_DOCUMENTS_UNION_QUERY = """
        SELECT
            'CONTRACT' as document_type,
            ec.id,
            ec.created_at,
            ec.updated_at,
            '근로계약서' as title,
            ec.status,
            creator.name as creator_name,
            employee.name as employee_name
        FROM employment_contract ec
        JOIN usrmst creator ON ec.creator_id = creator.id
        JOIN usrmst employee ON ec.employee_id = employee.id
        WHERE (:isAdmin = true OR ec.creator_id = :userId OR ec.employee_id = :userId)
          AND ec.status = 'COMPLETED'
        
        UNION ALL
        
        SELECT
            'LEAVE_APPLICATION' as document_type,
            la.id,
            la.created_at,
            la.updated_at,
            '휴가원' as title,
            la.status,
            applicant.name as creator_name,
            applicant.name as employee_name
        FROM leave_application la
        JOIN usrmst applicant ON la.applicant_id = applicant.id
        WHERE (:isAdmin = true OR la.applicant_id = :userId)
          AND la.status = 'APPROVED'
        
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
    """;

    private static final String COUNT_COMPLETED_DOCUMENTS_QUERY = """
        SELECT COUNT(*) FROM (
            SELECT ec.id
            FROM employment_contract ec
            WHERE (:isAdmin = true OR ec.creator_id = :userId OR ec.employee_id = :userId)
              AND ec.status = 'COMPLETED'
            
            UNION ALL
            
            SELECT la.id
            FROM leave_application la
            WHERE (:isAdmin = true OR la.applicant_id = :userId)
              AND la.status = 'APPROVED'
        ) combined_docs
    """;

    private static final String FIND_PENDING_DOCUMENTS_QUERY = """
    SELECT
        'CONTRACT' as document_type,
        ec.id,
        ec.created_at,
        ec.updated_at,
        '근로계약서' as title,
        ec.status,
        creator.name as creator_name,
        employee.name as employee_name
    FROM employment_contract ec
    JOIN usrmst creator ON ec.creator_id = creator.id
    JOIN usrmst employee ON ec.employee_id = employee.id
    WHERE ec.employee_id = :userId
      AND ec.status = 'SENT_TO_EMPLOYEE'
    
    UNION ALL
    
    SELECT
        'LEAVE_APPLICATION' as document_type,
        la.id,
        la.created_at,
        la.updated_at,
        '휴가원' as title,
        la.status,
        applicant.name as creator_name,
        applicant.name as employee_name
    FROM leave_application la
    JOIN usrmst applicant ON la.applicant_id = applicant.id
    WHERE la.current_approver_id = :userId
      AND la.status IN ('PENDING_SUBSTITUTE', 'PENDING_DEPT_HEAD', 'PENDING_CENTER_DIRECTOR',
                       'PENDING_ADMIN_DIRECTOR', 'PENDING_CEO_DIRECTOR')
    
    ORDER BY updated_at DESC
    LIMIT :limit OFFSET :offset
""";

    private static final String COUNT_PENDING_DOCUMENTS_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT ec.id
        FROM employment_contract ec
        WHERE ec.employee_id = :userId AND ec.status = 'SENT_TO_EMPLOYEE'
        
        UNION ALL
        
        SELECT la.id
        FROM leave_application la
        WHERE la.current_approver_id = :userId
          AND la.status IN ('PENDING_SUBSTITUTE', 'PENDING_DEPT_HEAD', 'PENDING_CENTER_DIRECTOR',
                           'PENDING_ADMIN_DIRECTOR', 'PENDING_CEO_DIRECTOR')
    ) combined_docs
""";

    // ⭐️ 인사팀 전용 PENDING 문서 조회 쿼리 추가
    private static final String FIND_PENDING_HR_STAFF_DOCUMENTS_QUERY = """
        SELECT
            'LEAVE_APPLICATION' as document_type,
            la.id,
            la.created_at,
            la.updated_at,
            '휴가원' as title,
            la.status,
            applicant.name as creator_name,
            applicant.name as employee_name
        FROM leave_application la
        JOIN usrmst applicant ON la.applicant_id = applicant.id
        WHERE la.status = 'PENDING_HR_STAFF'
        ORDER BY updated_at DESC
        LIMIT :limit OFFSET :offset
    """;

    // ⭐️ 인사팀 전용 PENDING 문서 개수 카운트 쿼리 추가
    private static final String COUNT_PENDING_HR_STAFF_DOCUMENTS_QUERY = """
        SELECT COUNT(*)
        FROM leave_application la
        WHERE la.status = 'PENDING_HR_STAFF'
    """;

    @Override
    public List<Object[]> findDraftDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_DRAFT_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countDraftDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_DRAFT_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findInProgressDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_IN_PROGRESS_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countInProgressDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_IN_PROGRESS_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findRejectedDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_REJECTED_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countRejectedDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_REJECTED_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findCompletedDocumentsUnion(String userId, boolean isAdmin, int limit, int offset) {
        return createNativeQuery(FIND_COMPLETED_DOCUMENTS_UNION_QUERY)
                .setParameter("userId", userId)
                .setParameter("isAdmin", isAdmin)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countCompletedDocuments(String userId, boolean isAdmin) {
        return ((Number) createNativeQuery(COUNT_COMPLETED_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .setParameter("isAdmin", isAdmin)
                .getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findPendingDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_PENDING_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countPendingDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_PENDING_DOCUMENTS_QUERY)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }
    // ⭐️ 인사팀 전용 PENDING 문서 조회 메소드 구현
    @Override
    public List<Object[]> findPendingHrStaffDocuments(int limit, int offset) {
        return createNativeQuery(FIND_PENDING_HR_STAFF_DOCUMENTS_QUERY)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    // ⭐️ 인사팀 전용 PENDING 문서 개수 카운트 메소드 구현
    @Override
    public long countPendingHrStaffDocuments() {
        return ((Number) createNativeQuery(COUNT_PENDING_HR_STAFF_DOCUMENTS_QUERY)
                .getSingleResult()).longValue();
    }

    private Query createNativeQuery(String sql) {
        return entityManager.createNativeQuery(sql);
    }
}