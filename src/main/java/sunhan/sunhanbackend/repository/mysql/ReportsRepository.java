package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface ReportsRepository {

    List<Object[]> findDraftDocuments(String userId, int limit, int offset);
    long countDraftDocuments(String userId);

    List<Object[]> findInProgressDocuments(String userId, int limit, int offset);
    long countInProgressDocuments(String userId);

    List<Object[]> findRejectedDocuments(String userId, int limit, int offset);
    long countRejectedDocuments(String userId);

    List<Object[]> findCompletedDocumentsUnion(String userId, boolean isAdmin, int limit, int offset);
    long countCompletedDocuments(String userId, boolean isAdmin);

    List<Object[]> findPendingDocuments(String userId, int limit, int offset);
    long countPendingDocuments(String userId);

    // 인사팀 전용 PENDING 문서
    List<Object[]> findPendingHrStaffDocuments(int limit, int offset);
    long countPendingHrStaffDocuments();

    // 계약서만 완료된 문서 조회
    List<Object[]> findCompletedContracts(String userId, boolean isAdmin, int limit, int offset);
    long countCompletedContracts(String userId, boolean isAdmin);

    // 휴가원만 완료된 문서 조회
    List<Object[]> findCompletedLeaveApplications(String userId, boolean isAdmin, int limit, int offset);
    long countCompletedLeaveApplications(String userId, boolean isAdmin);
}