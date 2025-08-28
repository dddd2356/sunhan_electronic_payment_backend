package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.response.ReportsResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.ContractType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.repository.mysql.ReportsRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegratedReportsService {

    private final ReportsRepository reportsRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    /**
     * 상태별 통합 문서 조회 (모든 상태에 대해 DB 레벨 최적화 적용)
     */
    public Page<ReportsResponseDto> getDocumentsByStatus(String userId, String status, boolean isAdmin, Pageable pageable) {
        int offset = (int) pageable.getOffset();
        int limit = pageable.getPageSize();

        List<Object[]> results;
        long totalCount;

        switch (status.toLowerCase()) {
            case "draft":
                results = reportsRepository.findDraftDocuments(userId, limit, offset);
                totalCount = reportsRepository.countDraftDocuments(userId);
                break;

            case "inprogress":
                results = reportsRepository.findInProgressDocuments(userId, limit, offset);
                totalCount = reportsRepository.countInProgressDocuments(userId);
                break;

            case "rejected":
                results = reportsRepository.findRejectedDocuments(userId, limit, offset);
                totalCount = reportsRepository.countRejectedDocuments(userId);
                break;

            case "completed":
                if (isAdmin) {
                    results = reportsRepository.findCompletedDocumentsUnion(userId, true, limit, offset);
                    totalCount = reportsRepository.countCompletedDocuments(userId, true);
                } else {
                    results = reportsRepository.findCompletedDocumentsUnion(userId, false, limit, offset);
                    totalCount = reportsRepository.countCompletedDocuments(userId, false);
                }
                break;

            case "pending":
                // 1. 현재 사용자 정보 조회
                UserEntity currentUser = userRepository.findByUserId(userId).orElse(null);

                // 2. 인사팀 권한 확인
                boolean hasHrContractPermission = permissionService.hasPermission(userId, PermissionType.HR_CONTRACT);
                boolean hasHrLeavePermission = permissionService.hasPermission(userId, PermissionType.HR_LEAVE_APPLICATION);

                // 3. 인사팀 소속 여부 판단 (jobLevel 0 또는 1, ADMIN 권한)
                boolean isHrStaff = currentUser != null &&
                        ("0".equals(currentUser.getJobLevel()) || "1".equals(currentUser.getJobLevel())) &&
                        currentUser.getRole() == Role.ADMIN &&
                        (hasHrContractPermission || hasHrLeavePermission);

                if (isHrStaff) {
                    Set<Long> addedIds = new HashSet<>();
                    List<Object[]> combinedResults = new ArrayList<>();

                    // HR_CONTRACT 권한이 있으면 근로계약서 문서 조회
                    if (hasHrContractPermission) {
                        List<Object[]> contractResults = reportsRepository.findPendingDocuments(userId, limit, offset);
                        for (Object[] r : contractResults) {
                            Long id = ((Number) r[1]).longValue();
                            if (addedIds.add(id)) combinedResults.add(r);
                        }
                    }

                    // HR_LEAVE_APPLICATION 권한이 있으면 휴가원 문서 조회
                    if (hasHrLeavePermission) {
                        List<Object[]> leaveResults = reportsRepository.findPendingHrStaffDocuments(limit, offset);
                        for (Object[] r : leaveResults) {
                            Long id = ((Number) r[1]).longValue();
                            if (addedIds.add(id)) combinedResults.add(r);
                        }
                    }

                    // updated_at 기준 내림차순 정렬
                    combinedResults.sort((a, b) -> ((Timestamp) b[3]).compareTo((Timestamp) a[3]));

                    results = combinedResults;

                    // totalCount 계산
                    totalCount = 0;
                    if (hasHrContractPermission) totalCount += reportsRepository.countPendingDocuments(userId);
                    if (hasHrLeavePermission) totalCount += reportsRepository.countPendingHrStaffDocuments();

                } else {
                    // 그 외 사용자는 자신에게 할당된 문서만 조회
                    results = reportsRepository.findPendingDocuments(userId, limit, offset);
                    totalCount = reportsRepository.countPendingDocuments(userId);
                }
                break;
            default:
                return Page.empty(pageable);
        }

        List<ReportsResponseDto> documents = results.stream()
                .map(this::mapToReportsDto)
                .collect(Collectors.toList());

        return new PageImpl<>(documents, pageable, totalCount);
    }

    /**
     * 상태별 문서 개수 조회도 DB 레벨에서 최적화
     */
    public Map<String, Long> getDocumentCounts(String userId, boolean isAdmin) {
        Map<String, Long> counts = new HashMap<>();

        // 병렬로 카운트 조회하여 성능 향상
        counts.put("draftCount", reportsRepository.countDraftDocuments(userId));
        counts.put("inProgressCount", reportsRepository.countInProgressDocuments(userId));
        counts.put("rejectedCount", reportsRepository.countRejectedDocuments(userId));

        // 1. 현재 사용자 정보 조회
        UserEntity currentUser = userRepository.findByUserId(userId).orElse(null);

        boolean hasHrContractPermission = permissionService.hasPermission(userId, PermissionType.HR_CONTRACT);
        boolean hasHrLeavePermission = permissionService.hasPermission(userId, PermissionType.HR_LEAVE_APPLICATION);

        // 2. 인사팀 소속인지 확인하는 로직 추가
        boolean isHrStaff = currentUser != null &&
                ("0".equals(currentUser.getJobLevel()) || "1".equals(currentUser.getJobLevel())) &&
                currentUser.getRole() == Role.ADMIN &&
                (hasHrContractPermission || hasHrLeavePermission);

        long pendingCount = 0;
        if (isHrStaff) {
            // 권한별로 문서 수 합산
            if (hasHrContractPermission) {
                pendingCount += reportsRepository.countPendingDocuments(userId);
            }
            if (hasHrLeavePermission) {
                pendingCount += reportsRepository.countPendingHrStaffDocuments();
            }
        } else {
            pendingCount = reportsRepository.countPendingDocuments(userId);
        }
        counts.put("pendingCount", pendingCount);

        // completed는 관리자 여부에 따라 다르게 조회
        counts.put("completedCount", reportsRepository.countCompletedDocuments(userId, isAdmin));

        return counts;
    }

    private ReportsResponseDto mapToReportsDto(Object[] row) {
        ReportsResponseDto dto = new ReportsResponseDto();

        String documentType = (String) row[0];
        dto.setId(((Number) row[1]).longValue());
        dto.setCreatedAt(((Timestamp) row[2]).toLocalDateTime());
        dto.setUpdatedAt(((Timestamp) row[3]).toLocalDateTime());
        dto.setTitle((String) row[4]);
        dto.setStatus((String) row[5]);
        dto.setApplicantName((String) row[6]);
        dto.setEmployeeName((String) row[7]);

        // 문서 타입 설정
        if ("CONTRACT".equals(documentType)) {
            dto.setType(ContractType.EMPLOYMENT_CONTRACT);
            dto.setRole("EMPLOYEE");
        } else {
            dto.setType(ContractType.LEAVE_APPLICATION);
            dto.setRole("CREATOR");
        }

        return dto;
    }
}

