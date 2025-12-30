package sunhan.sunhanbackend.repository.mysql;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReportsRepositoryImpl implements ReportsRepository {

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
                
                  UNION ALL
                                                         
                  SELECT
                     'WORK_SCHEDULE' as document_type,
                      ws.id,
                      ws.created_at,
                      ws.updated_at,
                      CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
                      ws.approval_status as status,
                      creator.name as creator_name,
                      creator.name as employee_name
                      FROM work_schedule ws
                      JOIN usrmst creator ON ws.created_by = creator.id
                      WHERE ws.created_by = :userId
                      AND ws.approval_status = 'DRAFT'
                      AND ws.is_active = true
                      
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
                    
            UNION ALL
                                                                                       
                SELECT ws.id
                    FROM work_schedule ws
                    WHERE ws.created_by = :userId
                    AND ws.approval_status = 'DRAFT'
                    AND ws.is_active = true
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
                  AND la.status IN ('PENDING')
                
                UNION ALL
                                              
                SELECT
                    'WORK_SCHEDULE' as document_type,
                    ws.id,
                    ws.created_at,
                    ws.updated_at,
                    CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
                    ws.approval_status as status,
                    creator.name as creator_name,
                    creator.name as employee_name
                    FROM work_schedule ws
                    JOIN usrmst creator ON ws.created_by = creator.id
                    WHERE ws.created_by = :userId
                    AND ws.approval_status = 'SUBMITTED'
                    AND ws.is_active = true
                                              
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
                      AND la.status IN ('PENDING')
                      
                    UNION ALL
                             
                    SELECT ws.id
                    FROM work_schedule ws
                    WHERE ws.created_by = :userId
                      AND ws.approval_status = 'SUBMITTED'
                      AND ws.is_active = true
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
                
                UNION ALL
                                                  
                SELECT
                'WORK_SCHEDULE' as document_type,
                ws.id,
                ws.created_at,
                ws.updated_at,
                CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
                ws.approval_status as status,
                creator.name as creator_name,
                creator.name as employee_name
                FROM work_schedule ws
                JOIN usrmst creator ON ws.created_by = creator.id
                WHERE ws.created_by = :userId
                AND ws.approval_status = 'REJECTED'
                AND ws.is_active = true
                                                  
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
                      
                    UNION ALL
                             
                              
                    SELECT ws.id
                    FROM work_schedule ws
                    WHERE ws.created_by = :userId
                       AND ws.approval_status = 'REJECTED'
                       AND ws.is_active = true
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
              
            UNION ALL
            
            SELECT
                'WORK_SCHEDULE' as document_type,
                ws.id,
                ws.created_at,
                ws.updated_at,
                CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
                ws.approval_status as status,
                creator.name as creator_name,
                creator.name as employee_name
            FROM work_schedule ws
            JOIN usrmst creator ON ws.created_by = creator.id
            WHERE ws.approval_status = 'APPROVED'
              AND ws.is_active = true
              AND (
                  :hasWorkSchedulePermission = true
                  OR ws.created_by = :userId
                  OR EXISTS (
                      SELECT 1
                      FROM work_schedule_entry wse
                      WHERE wse.work_schedule_id = ws.id
                        AND wse.user_id = :userId
                        AND wse.is_deleted = false
                  )
              )
            
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
                  
                UNION ALL
                
                SELECT ws.id
                FROM work_schedule ws
                WHERE ws.approval_status = 'APPROVED'
                  AND ws.is_active = true
                  AND (
                      :hasWorkSchedulePermission = true
                      OR ws.created_by = :userId
                      OR EXISTS (
                          SELECT 1
                          FROM work_schedule_entry wse
                          WHERE wse.work_schedule_id = ws.id
                            AND wse.user_id = :userId
                            AND wse.is_deleted = false
                      )
                  )
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
         LEFT JOIN approval_line al ON la.approval_line_id = al.id
         LEFT JOIN approval_step ast ON al.id = ast.approval_line_id
             AND ast.step_order = la.current_step_order
         LEFT JOIN document_approval_process dap ON dap.id = la.approval_process_id
         LEFT JOIN approval_step_history ash ON ash.approval_process_id = dap.id
             AND ash.approver_id = :userId
             AND ash.step_order = ast.step_order
         WHERE la.status IN ('PENDING')
           AND (
               (la.current_approver_id = :userId AND la.approval_line_id IS NULL)
               OR
               (ast.approver_id = :userId AND ash.id IS NULL AND la.approval_line_id IS NOT NULL)
           )
            
         UNION ALL
        
         SELECT
             'WORK_SCHEDULE' as document_type,
             ws.id,
             ws.created_at,
             ws.updated_at,
             CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
             ws.approval_status as status,
             creator.name as creator_name,
             creator.name as employee_name
         FROM work_schedule ws
         JOIN usrmst creator ON ws.created_by = creator.id
         JOIN approval_line al ON ws.approval_line_id = al.id
         JOIN approval_step ast ON al.id = ast.approval_line_id
         JOIN document_approval_process dap ON dap.document_id = ws.id
             AND dap.document_type = 'WORK_SCHEDULE'
         LEFT JOIN approval_step_history ash ON ash.approval_process_id = dap.id
             AND ash.approver_id = :userId
             AND ash.step_order = ast.step_order
         WHERE ast.approver_id = :userId
           AND ast.step_order = ws.current_approval_step
           AND ash.id IS NULL
           AND ws.approval_status = 'SUBMITTED'
           AND ws.is_active = true
        
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
             LEFT JOIN approval_line al ON la.approval_line_id = al.id
             LEFT JOIN approval_step ast ON al.id = ast.approval_line_id
                 AND ast.step_order = la.current_step_order
             LEFT JOIN document_approval_process dap ON dap.id = la.approval_process_id
             LEFT JOIN approval_step_history ash ON ash.approval_process_id = dap.id
                 AND ash.approver_id = :userId
                 AND ash.step_order = ast.step_order
             WHERE la.status IN ('PENDING')
               AND (
                   (la.current_approver_id = :userId AND la.approval_line_id IS NULL)
                   OR
                   (ast.approver_id = :userId AND ash.id IS NULL AND la.approval_line_id IS NOT NULL)
               )
                              
             UNION ALL
            
             SELECT ws.id
             FROM work_schedule ws
             JOIN approval_line al ON ws.approval_line_id = al.id
             JOIN approval_step ast ON al.id = ast.approval_line_id
             JOIN document_approval_process dap ON dap.document_id = ws.id
                 AND dap.document_type = 'WORK_SCHEDULE'
             LEFT JOIN approval_step_history ash ON ash.approval_process_id = dap.id
                 AND ash.approver_id = :userId
                 AND ash.step_order = ast.step_order
             WHERE ast.approver_id = :userId
               AND ast.step_order = ws.current_approval_step
               AND ash.id IS NULL
               AND ws.approval_status = 'SUBMITTED'
               AND ws.is_active = true
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
    LEFT JOIN approval_line al ON la.approval_line_id = al.id
    LEFT JOIN approval_step ast ON al.id = ast.approval_line_id
        AND ast.step_order = la.current_step_order
    WHERE la.status IN ('PENDING')
      AND ast.approver_type = 'HR_STAFF'
    
    UNION ALL
    
    SELECT
        'WORK_SCHEDULE' as document_type,
        ws.id,
        ws.created_at,
        ws.updated_at,
        CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
        ws.approval_status as status,
        creator.name as creator_name,
        creator.name as employee_name
    FROM work_schedule ws
    JOIN usrmst creator ON ws.created_by = creator.id
    JOIN approval_line al ON ws.approval_line_id = al.id
    JOIN approval_step ast ON al.id = ast.approval_line_id
        AND ast.step_order = ws.current_approval_step
    WHERE ws.approval_status = 'SUBMITTED'
      AND ws.is_active = true
      AND ast.approver_type = 'HR_STAFF'
    
    ORDER BY updated_at DESC
    LIMIT :limit OFFSET :offset
""";
    // ⭐️ 인사팀 전용 PENDING 문서 개수 카운트 쿼리 추가
    private static final String COUNT_PENDING_HR_STAFF_DOCUMENTS_QUERY = """
    SELECT COUNT(*) FROM (
        SELECT la.id
        FROM leave_application la
        LEFT JOIN approval_line al ON la.approval_line_id = al.id
        LEFT JOIN approval_step ast ON al.id = ast.approval_line_id
            AND ast.step_order = la.current_step_order
        WHERE la.status IN ('PENDING')
          AND ast.approver_type = 'HR_STAFF'
        
        UNION ALL
        
        SELECT ws.id
        FROM work_schedule ws
        JOIN approval_line al ON ws.approval_line_id = al.id
        JOIN approval_step ast ON al.id = ast.approval_line_id
            AND ast.step_order = ws.current_approval_step
        WHERE ws.approval_status = 'SUBMITTED'
          AND ws.is_active = true
          AND ast.approver_type = 'HR_STAFF'
    ) combined_docs
""";
    // =========================================
    // ✅ Completed Contracts 전용 쿼리
    // =========================================
    private static final String FIND_COMPLETED_CONTRACTS_QUERY = """
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
                ORDER BY ec.updated_at DESC
                LIMIT :limit OFFSET :offset
            """;
    private static final String COUNT_COMPLETED_CONTRACTS_QUERY = """
                SELECT COUNT(*)
                FROM employment_contract ec
                WHERE (:isAdmin = true OR ec.creator_id = :userId OR ec.employee_id = :userId)
                  AND ec.status = 'COMPLETED'
            """;
    // =========================================
    // ✅ Completed Leave Applications 전용 쿼리
    // =========================================
    private static final String FIND_COMPLETED_LEAVE_APPLICATIONS_QUERY = """
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
                ORDER BY la.updated_at DESC
                LIMIT :limit OFFSET :offset
            """;
    private static final String COUNT_COMPLETED_LEAVE_APPLICATIONS_QUERY = """
                SELECT COUNT(*)
                FROM leave_application la
                WHERE (:isAdmin = true OR la.applicant_id = :userId)
                  AND la.status = 'APPROVED'
            """;

    // 근무현황표 완료 문서 조회 (권한자 또는 포함된 직원)
    private static final String FIND_COMPLETED_WORK_SCHEDULES_QUERY = """
    SELECT
        'WORK_SCHEDULE' as document_type,
        ws.id,
        ws.created_at,
        ws.updated_at,
        CONCAT('근무현황표 (', ws.schedule_year_month, ')') as title,
        ws.approval_status as status,
        creator.name as creator_name,
        creator.name as employee_name
    FROM work_schedule ws
    JOIN usrmst creator ON ws.created_by = creator.id
    LEFT JOIN work_schedule_entry wse ON ws.id = wse.work_schedule_id
    WHERE ws.approval_status = 'APPROVED'
    AND ws.is_active = true
    AND (
        :hasPermission = true
        OR ws.created_by = :userId
        OR wse.user_id = :userId
    )
    GROUP BY ws.id
    ORDER BY ws.updated_at DESC
    LIMIT :limit OFFSET :offset
""";

    private static final String COUNT_COMPLETED_WORK_SCHEDULES_QUERY = """
        SELECT COUNT(DISTINCT ws.id)
        FROM work_schedule ws
        LEFT JOIN work_schedule_entry wse ON ws.id = wse.work_schedule_id
        WHERE ws.approval_status = 'APPROVED'
        AND ws.is_active = true
        AND (
            :hasPermission = true
            OR ws.created_by = :userId
            OR wse.user_id = :userId
        )
    """;
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Object[]> findDraftDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_DRAFT_DOCUMENTS_QUERY).setParameter("userId", userId).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countDraftDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_DRAFT_DOCUMENTS_QUERY).setParameter("userId", userId).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findInProgressDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_IN_PROGRESS_DOCUMENTS_QUERY).setParameter("userId", userId).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countInProgressDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_IN_PROGRESS_DOCUMENTS_QUERY).setParameter("userId", userId).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findRejectedDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_REJECTED_DOCUMENTS_QUERY).setParameter("userId", userId).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countRejectedDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_REJECTED_DOCUMENTS_QUERY).setParameter("userId", userId).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findCompletedDocumentsUnion(String userId, boolean isAdmin, int limit, int offset) {
        return createNativeQuery(FIND_COMPLETED_DOCUMENTS_UNION_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countCompletedDocuments(String userId, boolean isAdmin) {
        return ((Number) createNativeQuery(COUNT_COMPLETED_DOCUMENTS_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findPendingDocuments(String userId, int limit, int offset) {
        return createNativeQuery(FIND_PENDING_DOCUMENTS_QUERY).setParameter("userId", userId).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countPendingDocuments(String userId) {
        return ((Number) createNativeQuery(COUNT_PENDING_DOCUMENTS_QUERY).setParameter("userId", userId).getSingleResult()).longValue();
    }

    // ⭐️ 인사팀 전용 PENDING 문서 조회 메소드 구현
    @Override
    public List<Object[]> findPendingHrStaffDocuments(int limit, int offset) {
        return createNativeQuery(FIND_PENDING_HR_STAFF_DOCUMENTS_QUERY).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    // ⭐️ 인사팀 전용 PENDING 문서 개수 카운트 메소드 구현
    @Override
    public long countPendingHrStaffDocuments() {
        return ((Number) createNativeQuery(COUNT_PENDING_HR_STAFF_DOCUMENTS_QUERY).getSingleResult()).longValue();
    }

    private Query createNativeQuery(String sql) {
        return entityManager.createNativeQuery(sql);
    }

    // =========================================
    // ✅ 구현 메서드 추가
    // =========================================
    @Override
    public List<Object[]> findCompletedContracts(String userId, boolean isAdmin, int limit, int offset) {
        return createNativeQuery(FIND_COMPLETED_CONTRACTS_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countCompletedContracts(String userId, boolean isAdmin) {
        return ((Number) createNativeQuery(COUNT_COMPLETED_CONTRACTS_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findCompletedLeaveApplications(String userId, boolean isAdmin, int limit, int offset) {
        return createNativeQuery(FIND_COMPLETED_LEAVE_APPLICATIONS_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).setParameter("limit", limit).setParameter("offset", offset).getResultList();
    }

    @Override
    public long countCompletedLeaveApplications(String userId, boolean isAdmin) {
        return ((Number) createNativeQuery(COUNT_COMPLETED_LEAVE_APPLICATIONS_QUERY).setParameter("userId", userId).setParameter("isAdmin", isAdmin).getSingleResult()).longValue();
    }

    @Override
    public List<Object[]> findCompletedWorkSchedules(String userId, boolean hasPermission, int limit, int offset) {
        return createNativeQuery(FIND_COMPLETED_WORK_SCHEDULES_QUERY)
                .setParameter("userId", userId)
                .setParameter("hasPermission", hasPermission)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
    }

    @Override
    public long countCompletedWorkSchedules(String userId, boolean hasPermission) {
        return ((Number) createNativeQuery(COUNT_COMPLETED_WORK_SCHEDULES_QUERY)
                .setParameter("userId", userId)
                .setParameter("hasPermission", hasPermission)
                .getSingleResult()).longValue();
    }
}