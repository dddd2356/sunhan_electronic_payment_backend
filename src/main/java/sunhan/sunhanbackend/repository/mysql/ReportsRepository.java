package sunhan.sunhanbackend.repository.mysql;

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
    // 이 두 메소드를 추가합니다.
    List<Object[]> findPendingHrStaffDocuments(int limit, int offset);
    long countPendingHrStaffDocuments();
}