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

            case "completed": {
                UserEntity currentUserCompleted = userRepository.findByUserId(userId).orElse(null);
                String jobLevel = currentUserCompleted != null ? currentUserCompleted.getJobLevel() : null;
                Role userRole = currentUserCompleted != null ? currentUserCompleted.getRole() : null;

                Set<PermissionType> userPermissions = permissionService.getAllUserPermissions(userId);
                boolean hasHrContractPermissionCompleted = userPermissions.contains(PermissionType.HR_CONTRACT);
                boolean hasHrLeavePermissionCompleted = userPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);
                boolean hasWorkSchedulePermission = userPermissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);

                // 페치 사이즈: 각 타입별로 offset+limit 만큼 미리 가져와 병합 후 페이징
                int fetchSize = offset + limit;

                // 어떤 타입을 admin(전체)으로 볼지 결정하는 플래그들
                boolean contractsAdmin = false;
                boolean leavesAdmin = false;
                boolean workSchedulesAdmin = false; // workSchedulesAdmin == true 는 모든 근무표를 보여줘야 하는 상황

                // 결정 로직 (기존 권한 로직을 기반으로)
                if (("2".equals(jobLevel) || "6".equals(jobLevel)) && userRole == Role.ADMIN) {
                    // 최고권한: 모든 타입 전체 조회
                    contractsAdmin = true;
                    leavesAdmin = true;
                    workSchedulesAdmin = true;
                } else if (userRole == Role.ADMIN && hasHrContractPermissionCompleted && hasHrLeavePermissionCompleted) {
                    // ADMIN이고 둘다 HR 권한 보유: 모든 타입 전체 조회
                    contractsAdmin = true;
                    leavesAdmin = true;
                    workSchedulesAdmin = true;
                } else if (userRole == Role.ADMIN && hasHrContractPermissionCompleted) {
                    // 계약서는 전체, 휴가원은 본인 관련만
                    contractsAdmin = true;
                    leavesAdmin = false;
                    workSchedulesAdmin = hasWorkSchedulePermission;
                } else if (userRole == Role.ADMIN && hasHrLeavePermissionCompleted) {
                    // 휴가원은 전체, 계약서는 본인 관련만
                    contractsAdmin = false;
                    leavesAdmin = true;
                    workSchedulesAdmin = hasWorkSchedulePermission;
                } else {
                    // 그 외: 본인 관련 문서만
                    contractsAdmin = false;
                    leavesAdmin = false;
                    workSchedulesAdmin = hasWorkSchedulePermission;
                }

                // 타입별로 전용 조회
                List<Object[]> contractResults = reportsRepository.findCompletedContracts(userId, contractsAdmin, fetchSize, 0);
                List<Object[]> leaveResults = reportsRepository.findCompletedLeaveApplications(userId, leavesAdmin, fetchSize, 0);
                List<Object[]> workScheduleResults = reportsRepository.findCompletedWorkSchedules(userId, workSchedulesAdmin, fetchSize, 0);

                // 합치고 정렬 (updated_at 컬럼이 인덱스 3인 형식 유지)
                List<Object[]> combined = new ArrayList<>();
                combined.addAll(contractResults);
                combined.addAll(leaveResults);
                combined.addAll(workScheduleResults);

                combined.sort((a, b) -> ((Timestamp) b[3]).compareTo((Timestamp) a[3]));

                // totalCount 는 타입별 카운트 합산
                totalCount = reportsRepository.countCompletedContracts(userId, contractsAdmin)
                        + reportsRepository.countCompletedLeaveApplications(userId, leavesAdmin)
                        + reportsRepository.countCompletedWorkSchedules(userId, workSchedulesAdmin);

                // 페이징 슬라이스
                int fromIndex = Math.min(offset, combined.size());
                int toIndex = Math.min(offset + limit, combined.size());
                results = combined.subList(fromIndex, toIndex);

                break;
            }

            case "pending": {
                // 1. 현재 사용자 정보 조회
                UserEntity currentUser = userRepository.findByUserId(userId).orElse(null);

                // 2. 인사팀 권한 확인
                Set<PermissionType> currentUserPermissions = permissionService.getAllUserPermissions(userId);
                boolean hasHrContractPermission = currentUserPermissions.contains(PermissionType.HR_CONTRACT);
                boolean hasHrLeavePermission = currentUserPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);

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
            }
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

        Set<PermissionType> userPermissions = permissionService.getAllUserPermissions(userId);
        boolean hasHrContractPermission = userPermissions.contains(PermissionType.HR_CONTRACT);
        boolean hasHrLeavePermission = userPermissions.contains(PermissionType.HR_LEAVE_APPLICATION);
        // ✅ 근무표 관리 권한 확인
        boolean hasWorkSchedulePermission = userPermissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);

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

        // completed는 권한별로 조회
        String jobLevel = currentUser != null ? currentUser.getJobLevel() : null;
        Role userRole = currentUser != null ? currentUser.getRole() : null;

// 권한 변수들 이미 선언됨: hasHrContractPermission, hasHrLeavePermission, hasWorkSchedulePermission

        long completedCount;

// 결정 로직은 getDocumentsByStatus 와 동일하게 맞춘다
        if (("2".equals(jobLevel) || "6".equals(jobLevel)) && userRole == Role.ADMIN) {
            // 모든 타입 전체
            completedCount = reportsRepository.countCompletedContracts(userId, true)
                    + reportsRepository.countCompletedLeaveApplications(userId, true)
                    + reportsRepository.countCompletedWorkSchedules(userId, true);

        } else if (userRole == Role.ADMIN && hasHrContractPermission && hasHrLeavePermission) {
            // ADMIN + 둘다 HR 권한: 모든 타입 전체
            completedCount = reportsRepository.countCompletedContracts(userId, true)
                    + reportsRepository.countCompletedLeaveApplications(userId, true)
                    + reportsRepository.countCompletedWorkSchedules(userId, true);

        } else if (userRole == Role.ADMIN && hasHrContractPermission) {
            // 계약서는 전체, 휴가원은 본인 관련, 근무현황표는 권한 보유 시 전체/그렇지 않으면 참여자기준
            completedCount = reportsRepository.countCompletedContracts(userId, true)
                    + reportsRepository.countCompletedLeaveApplications(userId, false)
                    + reportsRepository.countCompletedWorkSchedules(userId, hasWorkSchedulePermission);

        } else if (userRole == Role.ADMIN && hasHrLeavePermission) {
            // 휴가원은 전체, 계약서는 본인 관련, 근무현황표는 권한 보유 시 전체/그렇지 않으면 참여자기준
            completedCount = reportsRepository.countCompletedLeaveApplications(userId, true)
                    + reportsRepository.countCompletedContracts(userId, false)
                    + reportsRepository.countCompletedWorkSchedules(userId, hasWorkSchedulePermission);

        } else {
            // 그 외: 본인 관련 문서만 (근무현황표는 작성자/참여자/권한 보유자 기준)
            completedCount = reportsRepository.countCompletedContracts(userId, false)
                    + reportsRepository.countCompletedLeaveApplications(userId, false)
                    + reportsRepository.countCompletedWorkSchedules(userId, hasWorkSchedulePermission);
        }

        counts.put("completedCount", completedCount);

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
        }  else if ("LEAVE_APPLICATION".equals(documentType)) {
            dto.setType(ContractType.LEAVE_APPLICATION);
            dto.setRole("CREATOR");
        }   else if ("WORK_SCHEDULE".equals(documentType)) {
            // ✅ 근무현황표 추가
            dto.setType(ContractType.WORK_SCHEDULE);
            dto.setRole("CREATOR");
        }

        return dto;
    }
}

