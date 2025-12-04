package sunhan.sunhanbackend.service.workschedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import sunhan.sunhanbackend.config.DepartmentProperties;
import sunhan.sunhanbackend.entity.mysql.Department;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStepHistory;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.entity.mysql.position.Position;
import sunhan.sunhanbackend.entity.mysql.workschedule.DeptDutyConfig;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalLineRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalStepHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.repository.mysql.position.PositionRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.DeptDutyConfigRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleEntryRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.approval.ApprovalProcessService;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkScheduleService {

    private final WorkScheduleRepository scheduleRepository;
    private final WorkScheduleEntryRepository entryRepository;
    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final ObjectMapper objectMapper;
    private final ApprovalLineRepository approvalLineRepository;
    private final PermissionService permissionService;
    private final DocumentApprovalProcessRepository processRepository;
    private final ApprovalStepHistoryRepository historyRepository;
    private final ApprovalProcessService approvalProcessService;
    private final DeptDutyConfigRepository dutyConfigRepository;
    private final DepartmentRepository departmentRepository;

    @Value("${holiday.api.key}")
    private String holidayApiKey;

    @Value("${holiday.api.url}")
    private String holidayApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 근무현황표 생성
     */
    @Transactional
    public WorkSchedule createSchedule(String deptCode, String yearMonth, String creatorId) {
        UserEntity user = userRepository.findByUserId(creatorId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        // 권한 검증 (기존 로직 유지)
        if (!user.getDeptCode().equals(deptCode)) {
            throw new SecurityException("본인 부서의 근무표만 생성할 수 있습니다.");
        }
        Set<PermissionType> permissions = permissionService.getAllUserPermissions(creatorId);
        boolean hasWorkSchedulePermission = permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);
        int jobLevel = Integer.parseInt(user.getJobLevel());
        boolean isDeptHead = jobLevel == 1;
        if (!hasWorkSchedulePermission && !isDeptHead) {
            throw new SecurityException("근무표를 생성할 권한이 없습니다. (부서장 또는 권한자만)");
        }

        // === 활성 레코드(즉, isActive = true)만 검사 ===
        boolean existsActive = scheduleRepository
                .existsByDeptCodeAndScheduleYearMonthAndIsActiveTrue(deptCode, yearMonth);
        if (existsActive) {
            throw new IllegalStateException("해당 년월의 활성 근무표가 이미 존재합니다.");
        }

        // 새 근무표 생성 (isActive 기본 true)
        WorkSchedule schedule = new WorkSchedule();
        schedule.setDeptCode(deptCode);
        schedule.setScheduleYearMonth(yearMonth);
        schedule.setCreatedBy(creatorId);
        schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.DRAFT);
        schedule.setIsActive(true);

        try {
            WorkSchedule saved = scheduleRepository.save(schedule);
            // 엔트리 자동 생성
            createEntriesForDeptUsers(saved);
            log.info("근무현황표 생성: id={}, dept={}, yearMonth={}", saved.getId(), deptCode, yearMonth);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // 동시성으로 다른 트랜잭션이 막아 실패한 경우 안전하게 처리
            throw new IllegalStateException("근무표 생성 중 동일한 활성 근무표가 이미 생성되었습니다. 다시 시도하세요.", ex);
        }
    }

    /**
     * 부서 직원들의 엔트리 자동 생성
     * 정렬 기준: 1순위 JobLevel(내림차순/높은직급순), 2순위 TotalVacationDays(내림차순/많은순)
     */
    private void createEntriesForDeptUsers(WorkSchedule schedule) {
        List<UserEntity> deptUsers = userRepository.findByDeptCodeAndUseFlag(
                schedule.getDeptCode(), "1");
        // 이전 달 근무표 ID 조회
        Long previousScheduleId = findPreviousMonthScheduleId(
                schedule.getScheduleYearMonth(),
                schedule.getDeptCode()
        );

        // 정렬 로직 수정 (JobLevel 높은 순 → 휴가 많은 순)
        deptUsers.sort((a, b) -> {
            // 1. JobLevel 비교 (숫자가 클수록 높은 직급이라고 가정 ex: 1=부서장, 0=사원)
            int aLevel = -1; // -1을 기본값으로 설정하여 null/파싱 오류 시 맨 뒤로 가지 않게 함
            int bLevel = -1;

            try {
                if (a.getJobLevel() != null) aLevel = Integer.parseInt(a.getJobLevel());
            } catch (NumberFormatException e) {}

            try {
                if (b.getJobLevel() != null) bLevel = Integer.parseInt(b.getJobLevel());
            } catch (NumberFormatException e) {}

            if (aLevel != bLevel) {
                // bLevel과 aLevel을 비교하여 내림차순 정렬 (숫자가 큰 사람이 앞으로)
                return Integer.compare(bLevel, aLevel);
            }

            // 2. JobLevel이 같으면 휴가 개수 비교 (내림차순/많은 순)
            Integer aTotal = a.getTotalVacationDays() != null ? a.getTotalVacationDays() : 0;
            Integer bTotal = b.getTotalVacationDays() != null ? b.getTotalVacationDays() : 0;
            return bTotal.compareTo(aTotal); // 내림차순
        });

        // 해당 년도에 APPROVED된 이전 근무표 합계 계산 (모든 이전 달)
        String currentYear = schedule.getScheduleYearMonth().split("-")[0];

        int order = 0;
        for (UserEntity user : deptUsers) {
            WorkScheduleEntry entry = new WorkScheduleEntry(schedule, user.getUserId(), order++);
            // 이전 달 의무 나이트 개수 가져오기
            Integer previousRequiredDuty = 0; // 기본값은 0

            if (previousScheduleId != null) {
                // Optional의 map().orElse()를 사용하여 람다 할당 오류를 해결하고 값을 가져옵니다.
                previousRequiredDuty = entryRepository.findByUserIdAndWorkScheduleId(user.getUserId(), previousScheduleId)
                        .map(prevEntry -> {
                            // 이전 달 엔트리에서 의무 나이트 개수를 가져오거나, null이면 0을 반환
                            return prevEntry.getNightDutyRequired() != null ? prevEntry.getNightDutyRequired() : 0;
                        })
                        .orElse(0); // 이전 달 엔트리 자체가 없으면 0을 반환
            }

            // 이번 달 엔트리에 이전 달 값으로 설정
            entry.setNightDutyRequired(previousRequiredDuty);

            // 의무 나이트 설정이 들어갔으므로, 추가 나이트도 초기화 시 계산합니다.
            int actual = entry.getNightDutyActual() != null ? entry.getNightDutyActual() : 0; // 초기에는 0
            int required = entry.getNightDutyRequired() != null ? entry.getNightDutyRequired() : 0; // 이전 달 값
            entry.setNightDutyAdditional(actual - required); // 초기에는 -previousRequiredDuty가 될 가능성이 높음

            // 휴가 총계 설정 (유지)
            entry.setVacationTotal(user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0);
            entry.setVacationUsedThisMonth(0.0);  // 초기 0

            // ✅ 새로 추가: 년도 첫 생성 확인 및 누적
            Double yearToDateUsed = entryRepository.sumApprovedVacationByUserIdAndYear(user.getUserId(), currentYear);
            if (yearToDateUsed == null) {
                yearToDateUsed = 0.0;
            }
            entry.setVacationUsedTotal(yearToDateUsed + entry.getVacationUsedThisMonth());  // 초기: 이전 합계 + 0

            // ✅ calculateStatistics 호출 (workData 기반 이번 달 재계산, but 초기 empty이므로 유지)
            calculateStatistics(entry);  // 필요시 내부에서 yearToDateUsed 재사용
            entryRepository.save(entry);
        }

        log.info("근무표 엔트리 생성 완료: scheduleId={}, count={}",
                schedule.getId(), deptUsers.size());
    }

    /**
     * [✅ 신규 메서드] 신규 부서원을 확인하고 근무표 엔트리를 추가합니다.
     * 이 메서드는 근무표 수정/임시저장 시점에 호출되어야 합니다.
     * @param schedule 현재 수정 중인 WorkSchedule 객체
     * @return 새로 추가된 엔트리 수
     */
    @Transactional
    public int addNewEntriesIfNecessary(WorkSchedule schedule) {
        // 1. 현재 부서의 모든 사용자 ID 조회
        List<String> currentDeptUserIds = userRepository.findByDeptCodeAndUseFlag(schedule.getDeptCode(), "1")
                .stream()
                .map(UserEntity::getUserId)
                .collect(Collectors.toList());

        // 2. 현재 근무표에 이미 존재하는 엔트리의 사용자 ID 조회
        Set<String> existingEntryUserIds = entryRepository.findByWorkScheduleIdOrderByDisplayOrderAsc(schedule.getId())
                .stream()
                .map(WorkScheduleEntry::getUserId)
                .collect(Collectors.toSet());

        // 3. 누락된 사용자 ID 식별 (현재 부서에 있지만 엔트리에는 없는 사용자)
        List<String> newComerUserIds = currentDeptUserIds.stream()
                .filter(userId -> !existingEntryUserIds.contains(userId))
                .collect(Collectors.toList());

        if (newComerUserIds.isEmpty()) {
            log.info("근무표 ID {}에 새로 추가된 직원이 없습니다.", schedule.getId());
            return 0;
        }

        log.info("근무표 ID {}에 신규 직원이 {}명 확인됨: {}", schedule.getId(), newComerUserIds.size(), newComerUserIds);

        // 4. 새로운 엔트리 생성 및 초기값 설정
        int newEntryCount = 0;

        // 이전 달의 근무표 ID를 찾습니다 (직전 나이트 의무 개수 초기값 설정을 위함)
        Long previousScheduleId = findPreviousMonthScheduleId(
                schedule.getScheduleYearMonth(),
                schedule.getDeptCode()
        );

        // 현재 엔트리들의 최대 displayOrder를 찾습니다.
        int maxOrder = existingEntryUserIds.isEmpty() ? 0 :
                entryRepository.findByWorkScheduleIdOrderByDisplayOrderAsc(schedule.getId())
                        .stream()
                        .mapToInt(WorkScheduleEntry::getDisplayOrder)
                        .max()
                        .orElse(0);

        for (String userId : newComerUserIds) {
            UserEntity user = userRepository.findByUserId(userId).orElse(null);
            if (user == null) continue;

            // 4-1. 새로운 WorkScheduleEntry 객체 생성
            WorkScheduleEntry newEntry = new WorkScheduleEntry(schedule, userId, ++maxOrder);

            // 4-2. 의무 나이트 개수 설정 (이전 달 데이터 사용)
            Integer previousRequiredDuty = 0;
            if (previousScheduleId != null) {
                previousRequiredDuty = entryRepository.findByUserIdAndWorkScheduleId(userId, previousScheduleId)
                        .map(prevEntry -> prevEntry.getNightDutyRequired() != null ? prevEntry.getNightDutyRequired() : 0)
                        .orElse(0);
            }
            newEntry.setNightDutyRequired(previousRequiredDuty);

            // 4-3. 휴가 총계 설정 (UserEntity 데이터 사용)
            newEntry.setVacationTotal(user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0);
            newEntry.setVacationUsedTotal(user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0);

            // 4-4. 저장
            entryRepository.save(newEntry);
            newEntryCount++;
        }

        return newEntryCount;
    }

    /**
     * [✅ 신규 메서드] 현재 부서원이 아닌(퇴사/이동한) 직원의 근무표 엔트리를 삭제합니다.
     * @param schedule 현재 수정 중인 WorkSchedule 객체
     * @return 삭제된 엔트리 수
     */
    @Transactional
    public int removeObsoleteEntriesIfNecessary(WorkSchedule schedule) {
        // 1. 현재 부서의 모든 사용자 ID 조회 (Active User)
        Set<String> currentDeptUserIds = userRepository.findByDeptCodeAndUseFlag(schedule.getDeptCode(), "1")
                .stream()
                .map(UserEntity::getUserId)
                .collect(Collectors.toSet());

        // 2. 현재 근무표에 존재하는 모든 엔트리 조회
        List<WorkScheduleEntry> existingEntries = entryRepository.findByWorkScheduleIdOrderByDisplayOrderAsc(schedule.getId());

        // 3. 삭제 대상 엔트리 식별 (근무표에는 있지만 현재 부서원 목록에는 없는 사용자)
        List<WorkScheduleEntry> entriesToRemove = existingEntries.stream()
                .filter(entry -> !currentDeptUserIds.contains(entry.getUserId()))
                .collect(Collectors.toList());

        if (entriesToRemove.isEmpty()) {
            log.info("근무표 ID {}에 삭제할 엔트리가 없습니다.", schedule.getId());
            return 0;
        }

        // 4. 삭제 실행
        int removedCount = entriesToRemove.size();
        entryRepository.deleteAll(entriesToRemove);

        log.warn("근무표 ID {}에서 부서원이 아닌 엔트리 {}개 삭제 완료.", schedule.getId(), removedCount);
        return removedCount;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getScheduleDetail(Long scheduleId, String userId) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다. "));

        validateScheduleDetailAccess(userId, schedule);

        List<WorkScheduleEntry> entries = entryRepository
                .findByWorkScheduleIdOrderByDisplayOrderAsc(scheduleId);

        List<Position> positions = positionRepository
                .findByDeptCodeAndIsActiveTrueOrderByDisplayOrderAsc(schedule.getDeptCode());

        Map<String, UserEntity> userMap = new HashMap<>();
        for (WorkScheduleEntry entry : entries) {
            userRepository.findByUserId(entry.getUserId())
                    .ifPresent(u -> userMap.put(u.getUserId(), u));
        }


        // ✅ 부서명 조회 - Department 테이블에서 직접 조회
        String deptName = departmentRepository.findById(schedule.getDeptCode())
                .map(Department::getDeptName)
                .orElse(schedule.getDeptCode()); // 조회 실패 시 부서코드 그대로 사용

        // ✅ 동적 결재라인 구성
        List<Map<String, Object>> approvalSteps = new ArrayList<>();

        DocumentApprovalProcess process = null;
        if (schedule.getApprovalLine() != null) {
            process = processRepository.findByDocumentIdAndDocumentType(
                    scheduleId,
                    DocumentType.WORK_SCHEDULE
            ).orElse(null);
        }

        // 1. 작성자 정보
        UserEntity creator = userRepository.findWithDeptByUserId(schedule.getCreatedBy()).orElse(null);

        if (creator != null) {
            Map<String, Object> creatorStep = new HashMap<>();
            creatorStep.put("stepOrder", 0);                    // ✅ Map에만 추가
            creatorStep.put("stepName", "작성");
            creatorStep.put("name", creator.getUserName());
            creatorStep. put("approverId", creator.getUserId());
            creatorStep.put("signatureUrl", schedule.getCreatorSignatureUrl());
            creatorStep.put("signedAt", schedule.getCreatorSignedAt());
            creatorStep.put("isSigned", schedule.getCreatorSignatureUrl() != null);

            boolean isCreatorAndDraft = schedule.getCreatedBy(). equals(userId) &&
                    schedule.getApprovalStatus() == WorkSchedule. ScheduleStatus.DRAFT;
            creatorStep.put("isCurrent", isCreatorAndDraft && schedule.getCreatorSignatureUrl() == null);

            approvalSteps.add(creatorStep);
        }

        // 2. 결재라인의 단계 추가
        if (schedule.getApprovalLine() != null) {
            List<ApprovalStep> steps = schedule.getApprovalLine().getSteps();
            Integer currentStep = schedule.getCurrentApprovalStep();

            for (int i = 0; i < steps.size(); i++) {
                ApprovalStep step = steps.get(i);

                Map<String, Object> stepInfo = new HashMap<>();

                // ✅ Map에만 추가 (기존 DTO는 그대로)
                stepInfo.put("stepOrder", step.getStepOrder());
                stepInfo. put("stepName", getStepName(step, steps));
                stepInfo.put("approverId", step.getApproverId());

                // 승인자명
                String approverName = "-";
                if (step.getApproverId() != null) {
                    UserEntity approver = userRepository.findByUserId(step.getApproverId()). orElse(null);
                    if (approver != null) {
                        approverName = approver.getUserName();
                    }
                }
                stepInfo. put("name", approverName);

                // 현재 단계 확인
                stepInfo.put("isCurrent",
                        schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus. SUBMITTED &&
                                currentStep != null && currentStep.equals(step.getStepOrder())
                );

                // 서명 완료 여부
                stepInfo.put("isSigned", currentStep != null && currentStep > step.getStepOrder());

                // ✅ 서명 완료 여부 - History에서 확인
                ApprovalStepHistory stepHistory = historyRepository
                        .findByApprovalProcessIdAndStepOrderAndAction(
                                process.getId(),
                                step.getStepOrder(),
                                ApprovalAction.APPROVED
                        )
                        .orElse(null);

                boolean isSigned = stepHistory != null;
                stepInfo.put("isSigned", isSigned);

                // ✅ 서명 이미지 - History에서 가져오기
                if (isSigned && stepHistory.getSignatureImageUrl() != null) {
                    stepInfo.put("signatureUrl", stepHistory.getSignatureImageUrl());
                    stepInfo.put("signedAt", stepHistory.getActionDate() != null ?
                            stepHistory.getActionDate().toString() : null);
                } else {
                    stepInfo.put("signatureUrl", null);
                    stepInfo.put("signedAt", null);
                }

                // ✅ process가 null인 경우 반려 정보 조회 불가
                if (process != null) {
                    // ✅ 반려 사유 확인
                    ApprovalStepHistory rejectedHistory = historyRepository
                            .findByApprovalProcessIdAndStepOrderAndAction(
                                    process.getId(),
                                    step.getStepOrder(),
                                    ApprovalAction.REJECTED
                            )
                            .orElse(null);

                    if (rejectedHistory != null) {
                        stepInfo.put("isRejected", true);
                        stepInfo.put("rejectionReason", rejectedHistory.getComment());
                        stepInfo.put("rejectedAt", rejectedHistory.getActionDate() != null ?
                                rejectedHistory.getActionDate().toString() : null);
                        stepInfo.put("rejectedBy", rejectedHistory.getApproverName());
                    } else {
                        stepInfo.put("isRejected", false);
                        stepInfo.put("rejectionReason", null);
                        stepInfo.put("rejectedAt", null);
                        stepInfo.put("rejectedBy", null);
                    }
                } else {
                    // process가 없는 경우 기본값
                    stepInfo.put("isRejected", false);
                    stepInfo.put("rejectionReason", null);
                    stepInfo.put("rejectedAt", null);
                    stepInfo.put("rejectedBy", null);
                }

                approvalSteps.add(stepInfo);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("schedule", schedule);
        result. put("entries", entries);
        result.put("positions", positions);
        result.put("users", userMap);
        result. put("yearMonth", schedule.getScheduleYearMonth());
        result.put("daysInMonth", getDaysInMonth(schedule.getScheduleYearMonth()));
        result.put("approvalSteps", approvalSteps);
        result.put("deptName", deptName);

        // scheduleId로 조회
        DeptDutyConfig dutyConfig = dutyConfigRepository.findByScheduleId(scheduleId)
                .orElse(null);

        if (dutyConfig == null) {
            log.info("근무표 {}의 당직 설정이 없습니다. 기본값 사용", scheduleId);
            dutyConfig = getDefaultConfig(scheduleId);
        }

        result.put("dutyConfig", dutyConfig);

        return result;
    }

    /**
     * ✅ 단계명 결정 헬퍼 메서드
     */
    private String getStepName(ApprovalStep step, List<ApprovalStep> allSteps) {
        // ✅ 부서장만 특별 처리
        if (step.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
            return "부서장";
        }

        // ✅ 마지막 단계는 무조건 "승인"
        if (step.getStepOrder() == allSteps.size()) {
            return "승인";
        }

        // ✅ 마지막 바로 전 단계는 "검토"
        if (step.getStepOrder() == allSteps.size() - 1) {
            return "검토";
        }

        // ✅ 그 외 중간 단계
        return "결재";
    }

    /**
     * ✅ 서명 날짜 조회 (기존 메서드 유지)
     */
    private String getSignedDateFromHistory(Long scheduleId, int stepOrder) {
        DocumentApprovalProcess process = processRepository.findByDocumentIdAndDocumentType(
                scheduleId,
                DocumentType.WORK_SCHEDULE
        ). orElse(null);

        if (process == null) return null;

        ApprovalStepHistory history = historyRepository
                .findByApprovalProcessIdAndStepOrderAndAction(
                        process.getId(),
                        stepOrder,
                        ApprovalAction.APPROVED
                )
                .orElse(null);

        if (history != null && history.getActionDate() != null) {
            return history.getActionDate().toString();
        }

        return null;
    }

    /**
     * ✅ 근무표 상세 조회 권한 검증 (최종)
     *
     * DRAFT: 작성자만
     * SUBMITTED: 작성자 + 현재 단계 결재자만
     * APPROVED: 작성자 + 같은 부서원만 (결재자 제외!)
     * REJECTED: 작성자만
     */
    private void validateScheduleDetailAccess(String userId, WorkSchedule schedule) {
        UserEntity user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다. "));

        String userDeptCode = user.getDeptCode();
        String scheduleDeptCode = schedule.getDeptCode();

        // ✅ [1] 작성자는 모든 상태에서 조회 가능
        if (schedule.getCreatedBy(). equals(userId)) {
            return;
        }

        WorkSchedule.ScheduleStatus status = schedule.getApprovalStatus();

        // ✅ [2] DRAFT 상태: 작성자만 (위에서 확인했으므로 거부)
        if (status == WorkSchedule.ScheduleStatus. DRAFT) {
            throw new SecurityException("임시저장 상태는 작성자만 조회할 수 있습니다.");
        }

        // ✅ [3] SUBMITTED 상태: 현재 단계 결재자만 추가로 가능
        if (status == WorkSchedule.ScheduleStatus. SUBMITTED) {
            if (isCurrentApprover(userId, schedule)) {
                return; // 현재 단계 결재자는 조회 가능
            }
            throw new SecurityException("제출된 상태는 현재 결재자만 조회할 수 있습니다.");
        }

        // ✅ [4] APPROVED 상태: 같은 부서원만 (결재자 제외!)
        if (status == WorkSchedule.ScheduleStatus. APPROVED) {
            if (userDeptCode != null && userDeptCode.equals(scheduleDeptCode)) {
                return; // 같은 부서원은 조회 가능
            }
            // ✅ 결재자는 여기서 제외 - 더 이상 조회 권한 없음
            throw new SecurityException("완료된 근무표는 같은 부서원만 조회할 수 있습니다.");
        }

        // ✅ [5] REJECTED 상태: 작성자만
        if (status == WorkSchedule. ScheduleStatus.REJECTED) {
            throw new SecurityException("반려된 상태는 작성자만 조회할 수 있습니다.");
        }

        // 그 외 상태
        throw new SecurityException("근무표를 조회할 권한이 없습니다.");
    }

    /**
     * ✅ 현재 단계 결재자인지 확인
     */
    private boolean isCurrentApprover(String userId, WorkSchedule schedule) {
        if (schedule.getApprovalLine() == null ||
                schedule.getCurrentApprovalStep() == null) {
            return false;
        }

        List<ApprovalStep> steps = schedule.getApprovalLine().getSteps();
        int currentStepIndex = schedule.getCurrentApprovalStep() - 1;

        if (currentStepIndex >= 0 && currentStepIndex < steps.size()) {
            ApprovalStep currentStep = steps.get(currentStepIndex);
            return currentStep.getApproverId() != null &&
                    currentStep. getApproverId().equals(userId);
        }

        return false;
    }

    /**
     * ✅ 작성자 서명 상태 업데이트 (임시저장/제출 시 호출)
     */
    @Transactional
    public void updateCreatorSignature(Long scheduleId, String userId, boolean isSigned) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        // 권한 및 상태 검증
        if (!schedule.getCreatedBy().equals(userId)) {
            throw new SecurityException("작성자 서명 권한이 없습니다.");
        }
        if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT &&
                schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.APPROVED) {
            throw new IllegalStateException("임시저장 또는 승인된 상태에서만 서명 변경이 가능합니다.");
        }


        if (isSigned) {
            // 서명 처리
            UserEntity user = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            if (user.getSignimage() != null) {
                String signatureUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(user.getSignimage());
                schedule.setCreatorSignatureUrl(signatureUrl);
                schedule.setCreatorSignedAt(LocalDateTime.now());
            }
        } else {
            // 서명 취소 (초기화)
            schedule.setCreatorSignatureUrl(null);
            schedule.setCreatorSignedAt(null);
        }

        scheduleRepository.save(schedule);
    }

    @Transactional
    public void updateScheduleRemarks(Long scheduleId, String userId, String remarks) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        validateScheduleEditable(schedule, userId);

        schedule.setRemarks(remarks);
        scheduleRepository.save(schedule);
    }

    /**
     * 근무표 수정 가능 여부 검증
     */
    private void validateScheduleEditable(WorkSchedule schedule, String userId) {
        if (schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus.APPROVED) {
            // 작성자인 경우 승인 상태여도 수정 허용 -> 여기서는 예외를 던지지 않고 넘어감
        } else if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
            // DRAFT도 아니고 APPROVED도 아니면 (즉, SUBMITTED, REVIEWED 등) 수정 불가
            throw new IllegalStateException("현재 상태에서는 수정할 수 없습니다.");
        }

        if (!schedule.getCreatedBy().equals(userId)) {
            throw new SecurityException("근무표를 수정할 권한이 없습니다.");
        }
    }

    @Transactional
    public void submitWithApprovalLine(Long scheduleId, String userId, Long approvalLineId) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        if (!schedule.getCreatedBy().equals(userId)) {
            throw new SecurityException("제출 권한이 없습니다.");
        }

        if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
            throw new IllegalStateException("임시저장 상태에서만 제출할 수 있습니다.");
        }

        ApprovalLine approvalLine = approvalLineRepository.findById(approvalLineId)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));

        // ✅ 작성자 서명이 없으면 제출 불가
        if (schedule.getCreatorSignatureUrl() == null || schedule.getCreatorSignatureUrl().isEmpty()) {
            throw new IllegalStateException("작성자 서명이 필요합니다.");
        }

        schedule.setApprovalLine(approvalLine);
        schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.SUBMITTED);
        schedule.setCurrentApprovalStep(1);  // ✅ 작성자 서명 완료 → 1번 결재자 대기

        // ✅ ApprovalProcess 생성
        DocumentApprovalProcess process = approvalProcessService.startProcessWithSteps(
                scheduleId,
                DocumentType.WORK_SCHEDULE,
                approvalLine,
                approvalLine.getSteps(),
                userId
        );

        scheduleRepository.save(schedule);
        log.info("근무표 제출 완료: scheduleId={}, approvalLineId={}", scheduleId, approvalLineId);
    }

    @Transactional
    public void signCurrentStep(Long scheduleId, String userId, Integer stepOrder) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.SUBMITTED) {
            throw new IllegalStateException("제출된 상태에서만 서명할 수 있습니다.");
        }

        ApprovalLine approvalLine = schedule.getApprovalLine();
        if (approvalLine == null) {
            throw new IllegalStateException("결재라인이 설정되지 않았습니다.");
        }

        if (!Objects.equals(schedule.getCurrentApprovalStep(), stepOrder)) {
            throw new IllegalStateException("현재 결재 단계가 아닙니다.");
        }

        List<ApprovalStep> steps = approvalLine.getSteps();
        if (stepOrder >= steps.size()) {
            throw new IllegalStateException("유효하지 않은 결재 단계입니다.");
        }

        ApprovalStep currentStep = steps.get(stepOrder);

        if (!Objects.equals(currentStep.getApproverId(), userId)) {
            throw new SecurityException("해당 단계의 서명 권한이 없습니다.");
        }

        // -----------------------------
        // 핵심: DocumentApprovalProcess 가져오기
        // -----------------------------
        DocumentApprovalProcess process = processRepository
                .findByDocumentIdAndDocumentType(scheduleId, DocumentType.WORK_SCHEDULE)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        // 서명 이미지(가능하면 전달)
        UserEntity approver = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        String signatureImageUrl = null;
        if (approver.getSignimage() != null) {
            signatureImageUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(approver.getSignimage());
        }

        // 마지막 단계 판단 (isFinalApproval)
        boolean isFinalApproval = false;
        Integer lastStepOrder = steps.stream()
                .map(ApprovalStep::getStepOrder)
                .max(Integer::compareTo)
                .orElse(stepOrder);
        if (Objects.equals(stepOrder, lastStepOrder)) {
            isFinalApproval = true;
        }

        // ApprovalProcessService에 승인 요청 (이곳에서 history, process 이동, 전결 처리 등 처리)
        approvalProcessService.approveStep(process.getId(), userId, null, signatureImageUrl, isFinalApproval);

        // approveStep() 수행 후에는 process의 상태가 바뀌었을 테니 최신으로 다시 읽어 온다
        DocumentApprovalProcess updated = processRepository.findById(process.getId())
                .orElse(process);

        // process의 currentStepOrder나 status를 기준으로 WorkSchedule 동기화
        schedule.setCurrentApprovalStep(updated.getCurrentStepOrder()); // null일 수 있음
        if (updated.getStatus() == sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus.APPROVED) {
            schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
            schedule.setIsPrintable(true);
        }
        // 반대로 REJECTED일 경우도 처리 필요 (요구시)
        if (updated.getStatus() == sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus.REJECTED) {
            schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.REJECTED);
        }

        scheduleRepository.save(schedule);

        log.info("근무표 서명 완료 (통합 프로세스): scheduleId={}, userId={}, stepOrder={}, processId={}",
                scheduleId, userId, stepOrder, process.getId());
    }

    /**
     * 근무 데이터 업데이트 (일괄)
     */
    @Transactional
    public void updateWorkData(Long scheduleId, String userId,
                               List<Map<String, Object>> updates) throws JsonProcessingException {
        try {
            // 권한 및 상태 검증
            WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));
            removeObsoleteEntriesIfNecessary(schedule);
            addNewEntriesIfNecessary(schedule);

            // ✅ DRAFT 상태이고 작성자만 편집 가능
            WorkSchedule.ScheduleStatus status = schedule.getApprovalStatus();
            if (status != WorkSchedule.ScheduleStatus.DRAFT && status != WorkSchedule.ScheduleStatus.APPROVED) {
                throw new IllegalStateException("작성 중이거나 승인된 상태에서만 수정할 수 있습니다.");
            }

            if (! schedule.getCreatedBy().equals(userId)) {
                throw new SecurityException("작성자만 근무표를 수정할 수 있습니다.");
            }

            for (Map<String, Object> update : updates) {
                Long entryId = Long.valueOf(update.get("entryId").toString());
                String workDataJson = objectMapper.writeValueAsString(update.get("workData"));

                // ❌ 문제의 발생 지점 수정: orElseThrow 대신 null 체크로 대체 (이미 있음, 유지)
                WorkScheduleEntry entry = entryRepository.findById(entryId).orElse(null);
                if (entry == null) {
                    log.warn("업데이트 목록에 포함된 엔트리 ID {}는 DB에 존재하지 않아 스킵합니다 (삭제된 엔트리일 수 있음).", entryId);
                    continue; // 다음 업데이트 항목으로 넘어갑니다.
                }

                entry.setWorkDataJson(workDataJson);

                // ✅ 새로 추가: positionId 업데이트
                if (update.containsKey("positionId")) {
                    Long positionId = update.get("positionId") != null ? Long.valueOf(update.get("positionId").toString()) : null;
                    entry.setPositionId(positionId);
                }

                // ✅ remarks도 이미 처리됨 (유지)
                if (update.containsKey("remarks")) {
                    entry.setRemarks((String) update.get("remarks"));
                }

                // 통계 계산 및 저장 (유지)
                calculateStatistics(entry);
                schedule.setUpdatedAt(LocalDateTime.now());
                entryRepository.save(entry);
            }

            log.info("근무 데이터 업데이트: scheduleId={}, count={}", scheduleId, updates.size());

        } catch (Exception e) {
            log.error("근무 데이터 업데이트 실패", e);
            throw new RuntimeException("근무 데이터 업데이트 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * ✅ 통계 계산 (부서 설정 기반 + 공휴일 API 연동)
     */
    private void calculateStatistics(WorkScheduleEntry entry) {
        try {
            if (entry.getWorkDataJson() == null) return;

            WorkSchedule schedule = entry.getWorkSchedule();
            String deptCode = schedule.getDeptCode();

            // 부서별 당직 설정 조회
            DeptDutyConfig config = dutyConfigRepository.findByScheduleId(schedule.getId())
                    .orElse(getDefaultConfig(schedule.getId()));

            Map<String, String> workData = objectMapper.readValue(
                    entry.getWorkDataJson(), Map.class);

            int totalDuty = 0;
            int offCount = 0;
            double vacationUsed = 0.0;

            // 당직 모드 세부 카운트
            Map<String, Integer> detailCount = new HashMap<>();
            if (config.getDutyMode() == DeptDutyConfig.DutyMode.ON_CALL_DUTY) {
                if (config.getUseWeekday()) detailCount.put("평일", 0);
                if (config.getUseFriday()) detailCount.put("금요일", 0);
                if (config.getUseSaturday()) detailCount.put("토요일", 0);
                if (config.getUseHolidaySunday()) detailCount.put("공휴일 및 일요일", 0);
            }

            // 월의 정보 및 공휴일 조회
            String yearMonth = schedule.getScheduleYearMonth();
            Set<String> holidays = loadHolidaysForMonth(yearMonth);

            for (Map.Entry<String, String> dayEntry : workData.entrySet()) {
                String dayStr = dayEntry.getKey();
                String value = dayEntry.getValue();

                if (value == null || value.trim().isEmpty()) continue;
                if (dayStr.equals("rowType") || dayStr.equals("longTextValue")) continue;

                String trimmed = value.trim().toUpperCase();

                // ✅ 당직/나이트 판별
                if (config.getDutyMode() == DeptDutyConfig.DutyMode.NIGHT_SHIFT) {
                    // 나이트 모드: N만 인식
                    if (trimmed.equals("N") || trimmed.startsWith("NIGHT")) {
                        totalDuty++;
                    }
                } else {
                    // 당직 모드: cellSymbol 기반
                    String symbol = config.getCellSymbol().toUpperCase();

                    if (trimmed.equals(symbol) ||
                            trimmed.startsWith(symbol) ||
                            trimmed.matches(symbol + "[1-3]")) {

                        totalDuty++;

                        // ✅ 요일별 분류
                        try {
                            int day = Integer.parseInt(dayStr);
                            String dayOfWeek = getDayOfWeek(yearMonth, day);
                            boolean isHoliday = holidays.contains(formatHolidayKey(yearMonth, day));

                            // N1/N2/N3 형식 체크
                            if (trimmed.endsWith("1")) {
                                detailCount.merge("평일", 1, Integer::sum);
                            } else if (trimmed.endsWith("2")) {
                                detailCount.merge("토요일", 1, Integer::sum);
                            } else if (trimmed.endsWith("3")) {
                                detailCount.merge("공휴일 및 일요일", 1, Integer::sum);
                            } else {
                                // ✅ 자동 요일 판별
                                if (isHoliday || dayOfWeek.equals("일")) {
                                    detailCount.merge("공휴일 및 일요일", 1, Integer::sum);
                                } else if (dayOfWeek.equals("토")) {
                                    detailCount.merge("토요일", 1, Integer::sum);
                                } else if (dayOfWeek.equals("금") && config.getUseFriday()) {
                                    detailCount.merge("금요일", 1, Integer::sum);
                                } else {
                                    detailCount.merge("평일", 1, Integer::sum);
                                }
                            }
                        } catch (NumberFormatException e) {
                            // 날짜가 아닌 키는 무시
                        }
                    }
                }

                // HN 처리
                if (trimmed.equals("HN")) {
                    totalDuty++;
                    vacationUsed += 0.5;
                }

                // OFF 카운트
                if (trimmed.startsWith("OFF")) {
                    offCount++;
                }

                // 연차 계산
                if (trimmed.contains("연") || trimmed.equals("AL") || trimmed.equals("ANNUAL")) {
                    vacationUsed += 1;
                } else if (trimmed.equals("반차") || trimmed.equals("HD") || trimmed.equals("HE")) {
                    vacationUsed += 0.5;
                }
            }

            // 1. 이번 달 통계 세팅
            entry.setNightDutyActual(totalDuty);
            entry.setNightDutyAdditional(totalDuty - (entry.getNightDutyRequired() != null ? entry.getNightDutyRequired() : 0));
            entry.setOffCount(offCount);
            entry.setVacationUsedThisMonth(vacationUsed); // 이번 달 사용량 저장

            // 2. 연간 누적 사용량 계산 (올해 다른 달 합계 + 이번 달)
            String currentYear = schedule.getScheduleYearMonth().split("-")[0];

            // ✅ 수정: 해당 년도 APPROVED된 이전 달 합계만 (exclude current)
            Double otherMonthsUsed = entryRepository.sumApprovedVacationByUserIdAndYearExcludingCurrent(
                    entry.getUserId(), currentYear, schedule.getId()
            );
            if (otherMonthsUsed == null) {
                otherMonthsUsed = 0.0;
            }

            // 총계 = 다른 달 누적 사용량 + 이번 달 사용량
            entry.setVacationUsedTotal(otherMonthsUsed + vacationUsed);
            entry.setVacationUsedThisMonth(vacationUsed);

            // 결과 저장
            entry.setNightDutyActual(totalDuty);
            entry.setNightDutyAdditional(totalDuty - (entry.getNightDutyRequired() != null ? entry.getNightDutyRequired() : 0));
            entry.setOffCount(offCount);
            entry.setVacationUsedThisMonth(vacationUsed);

            // 당직 세부 정보 저장
            if (!detailCount.isEmpty()) {
                entry.setDutyDetailJson(objectMapper.writeValueAsString(detailCount));
            } else {
                entry.setDutyDetailJson(null);
            }

        } catch (Exception e) {
            log.error("통계 계산 실패: entryId={}", entry.getId(), e);
        }
    }

    /**
     * ✅ 해당 월의 공휴일 정보 로드 (API 호출)
     */
    private Set<String> loadHolidaysForMonth(String yearMonth) {
        Set<String> holidaySet = new HashSet<>();

        try {
            String[] parts = yearMonth.split("-");
            int year = Integer.parseInt(parts[0]);

            String url = String.format(
                    "%s?serviceKey=%s&solYear=%d&numOfRows=100&_type=json",
                    holidayApiUrl, holidayApiKey, year
            );

            String response = restTemplate.getForObject(url, String.class);

            // JSON 파싱
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            com.fasterxml.jackson.databind.JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : items) {
                    String locdate = item.path("locdate").asText();
                    if (locdate.length() == 8) {
                        String month = locdate.substring(4, 6);
                        String day = locdate.substring(6, 8);
                        holidaySet.add(Integer.parseInt(month) + "-" + Integer.parseInt(day));
                    }
                }
            } else if (items.isObject()) {
                // 단일 아이템인 경우
                String locdate = items.path("locdate").asText();
                if (locdate.length() == 8) {
                    String month = locdate.substring(4, 6);
                    String day = locdate.substring(6, 8);
                    holidaySet.add(Integer.parseInt(month) + "-" + Integer.parseInt(day));
                }
            }

        } catch (Exception e) {
            log.warn("공휴일 조회 실패, 빈 Set 반환: yearMonth={}", yearMonth, e);
        }

        return holidaySet;
    }

    /**
     * ✅ 공휴일 키 포맷 (월-일)
     */
    private String formatHolidayKey(String yearMonth, int day) {
        String[] parts = yearMonth.split("-");
        int month = Integer.parseInt(parts[1]);
        return month + "-" + day;
    }

    /**
     * ✅ 요일 계산
     */
    private String getDayOfWeek(String yearMonth, int day) {
        try {
            String[] parts = yearMonth.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);

            java.time.LocalDate date = java.time.LocalDate.of(year, month, day);
            java.time.DayOfWeek dow = date.getDayOfWeek();

            String[] koreanDays = {"월", "화", "수", "목", "금", "토", "일"};
            return koreanDays[dow.getValue() - 1];
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * ✅ 기본 설정
     */
    private DeptDutyConfig getDefaultConfig(Long scheduleId) {
        DeptDutyConfig config = new DeptDutyConfig();
        config.setScheduleId(scheduleId);  // scheduleId
        config.setDutyMode(DeptDutyConfig.DutyMode.NIGHT_SHIFT);
        config.setDisplayName("나이트");
        config.setCellSymbol("N");
        return config;
    }

    /**
     * 해당 월의 일수 계산
     */
    private int getDaysInMonth(String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        return ym.lengthOfMonth();
    }

    /**
     * 부서 접근 권한 검증
     */
    private void validateDeptAccess(String userId, String deptCode) {
        UserEntity user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        int jobLevel = Integer.parseInt(user.getJobLevel());

        // ✅ WORK_SCHEDULE_MANAGE 권한 확인
        Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
        boolean hasWorkSchedulePermission = permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);

        // 권한이 있으면 본인 부서만 접근 가능
        if (hasWorkSchedulePermission) {
            if (!user.getDeptCode().equals(deptCode)) {
                throw new SecurityException("본인 부서의 근무표만 접근할 수 있습니다.");
            }
            return;
        }

        // 부서장은 자기 부서만
        if (jobLevel == 1 && !user.getDeptCode().equals(deptCode)) {
            throw new SecurityException("해당 부서의 근무표에 접근할 권한이 없습니다.");
        }

        // 권한도 없고 부서장도 아닌 경우
        if (jobLevel == 0) {
            throw new SecurityException("근무표를 조회할 권한이 없습니다.");
        }
    }

    /**
     * 의무 나이트 개수 수동 설정
     */
    @Transactional
    public void updateNightDutyRequired(Long entryId, Integer requiredCount, String userId) {
        WorkScheduleEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new EntityNotFoundException("엔트리를 찾을 수 없습니다."));

        WorkSchedule schedule = entry.getWorkSchedule();

        // ✅ DRAFT 상태 or APPROVED 상태 + 작성자만 수정 가능
        WorkSchedule.ScheduleStatus status = schedule.getApprovalStatus();
        if (status != WorkSchedule.ScheduleStatus.DRAFT && status != WorkSchedule.ScheduleStatus.APPROVED) {
            throw new IllegalStateException("작성 중이거나 승인된 상태에서만 수정할 수 있습니다.");
        }

        if (!schedule.getCreatedBy().equals(userId)) {
            throw new SecurityException("작성자만 수정할 수 있습니다.");
        }

        entry.setNightDutyRequired(requiredCount != null ? requiredCount : 0);

        int actual = entry.getNightDutyActual() != null ? entry.getNightDutyActual() : 0;
        int required = entry.getNightDutyRequired() != null ? entry.getNightDutyRequired() : 0;
        entry.setNightDutyAdditional(actual - required);

        entryRepository.save(entry);
    }

    /**
     * 여러 엔트리의 의무 나이트 개수 일괄 설정
     */
    @Transactional
    public void updateMultipleNightDutyRequired(Long scheduleId, String userId,
                                                Map<Long, Integer> entryRequiredMap) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        validateScheduleEditable(schedule, userId);
        // 수정(임시저장)이 일어날 때 신규 부서원을 추가합니다.
        // 기존 엔트리 업데이트 전에 실행하여 신규 엔트리가 DB에 생성되도록 합니다.
        addNewEntriesIfNecessary(schedule);

        for (Map.Entry<Long, Integer> entry : entryRequiredMap.entrySet()) {
            Long entryId = entry.getKey();
            Integer requiredCount = entry.getValue();

            updateNightDutyRequired(entryId, requiredCount, userId);
        }

        log.info("의무 나이트 개수 일괄 설정 완료: scheduleId={}, count={}",
                scheduleId, entryRequiredMap.size());
    }

    /**
     * 이전 달의 WorkSchedule ID를 찾는 헬퍼 메서드
     * @param currentScheduleYearMonth 현재 근무표의 'YYYY-MM' 문자열
     * @param deptCode 부서 코드
     * @return 이전 달의 WorkSchedule ID (없으면 null)
     */
    private Long findPreviousMonthScheduleId(String currentScheduleYearMonth, String deptCode) {
        try {
            // 1. 현재 연월을 YearMonth 객체로 파싱
            YearMonth current = YearMonth.parse(currentScheduleYearMonth, DateTimeFormatter.ofPattern("yyyy-MM"));
            // 2. 이전 달 계산
            YearMonth previous = current.minusMonths(1);
            String previousYearMonth = previous.format(DateTimeFormatter.ofPattern("yyyy-MM"));

            // 3. 이전 달, 동일 부서, 승인(APPROVED) 또는 확정된(DRAFT도 가능) 스케줄을 찾습니다.
            // 여기서는 최종 확정된(APPROVED) 근무표를 기준으로 가져오는 것이 가장 정확합니다.
            return scheduleRepository.findByScheduleYearMonthAndDeptCodeAndApprovalStatus(
                            previousYearMonth,
                            deptCode,
                            WorkSchedule.ScheduleStatus.APPROVED)
                    .map(WorkSchedule::getId)
                    .orElse(null);

        } catch (Exception e) {
            log.warn("이전 달 근무표 ID 찾기 실패: {} 부서, 연월={}", deptCode, currentScheduleYearMonth, e);
            return null;
        }
    }
}