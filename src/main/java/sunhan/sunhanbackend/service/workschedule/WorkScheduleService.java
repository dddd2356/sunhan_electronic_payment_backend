package sunhan.sunhanbackend.service.workschedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStepHistory;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.entity.mysql.position.Position;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalLineRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalStepHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.repository.mysql.position.PositionRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleEntryRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.approval.ApprovalProcessService;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

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
     * 부서 직원들의 엔트리 자동 생성 (totalVacationDays 높은 순)
     */
    private void createEntriesForDeptUsers(WorkSchedule schedule) {
        List<UserEntity> deptUsers = userRepository.findByDeptCodeAndUseFlag(
                schedule.getDeptCode(), "1");

        // ✅ totalVacationDays 내림차순 정렬 (높은 순서)
        deptUsers.sort((a, b) -> {
            Integer aTotal = a.getTotalVacationDays() != null ? a.getTotalVacationDays() : 0;
            Integer bTotal = b.getTotalVacationDays() != null ? b.getTotalVacationDays() : 0;
            return bTotal.compareTo(aTotal); // 내림차순
        });

        int order = 0;
        for (UserEntity user : deptUsers) {
            WorkScheduleEntry entry = new WorkScheduleEntry(schedule, user.getUserId(), order++);

            // 휴가 정보 설정
            entry.setVacationTotal(user.getTotalVacationDays() != null ? user.getTotalVacationDays() : 15.0);
            entry.setVacationUsedTotal(user.getUsedVacationDays() != null ? user.getUsedVacationDays() : 0.0);

            entryRepository.save(entry);
        }

        log.info("근무표 엔트리 생성 완료: scheduleId={}, count={}",
                schedule.getId(), deptUsers.size());
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

        // ✅ 동적 결재라인 구성 (Map 사용 - DTO 변경 없음)
        List<Map<String, Object>> approvalSteps = new ArrayList<>();

        DocumentApprovalProcess process = null;
        if (schedule.getApprovalLine() != null) {
            process = processRepository.findByDocumentIdAndDocumentType(
                    scheduleId,
                    DocumentType.WORK_SCHEDULE
            ).orElse(null);
        }

        // 1. 작성자 정보
        UserEntity creator = userRepository.findByUserId(schedule.getCreatedBy()). orElse(null);
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
        if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
            throw new IllegalStateException("임시저장 상태에서만 서명 변경이 가능합니다.");
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
            throw new IllegalStateException("승인된 근무표는 수정할 수 없습니다.");
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

            // ✅ DRAFT 상태이고 작성자만 편집 가능
            if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
                throw new IllegalStateException("임시저장 상태에서만 수정할 수 있습니다.");
            }

            if (! schedule.getCreatedBy().equals(userId)) {
                throw new SecurityException("작성자만 근무표를 수정할 수 있습니다.");
            }

            for (Map<String, Object> update : updates) {
                Long entryId = Long.valueOf(update.get("entryId").toString());
                String workDataJson = objectMapper.writeValueAsString(update.get("workData"));

                WorkScheduleEntry entry = entryRepository.findById(entryId)
                        .orElseThrow(() -> new EntityNotFoundException("엔트리를 찾을 수 없습니다."));

                entry.setWorkDataJson(workDataJson);

                // 통계 계산
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
     * 통계 자동 계산 (나이트, OFF 개수 등)
     */
    private void calculateStatistics(WorkScheduleEntry entry) {
        try {
            if (entry.getWorkDataJson() == null) return;

            Map<String, String> workData = objectMapper.readValue(
                    entry.getWorkDataJson(), Map.class);

            int nightCount = 0;
            int offCount = 0;
            double vacationUsed = 0.0;

            for (String value : workData.values()) {
                if (value == null || value.trim().isEmpty()) continue;

                String trimmedValue = value.trim().toUpperCase();
                // N 또는 Night로 시작하는 값을 나이트로 간주
                if ("N".equalsIgnoreCase(trimmedValue) ||
                        trimmedValue.toUpperCase().startsWith("NIGHT")) {
                    nightCount++;
                }
                else if ("HN".equalsIgnoreCase(trimmedValue)) {
                    nightCount++;
                    vacationUsed += 0.5;
                }

                // Off로 시작하는 값을 OFF로 간주 (대소문자 무관)
                else if (trimmedValue.toUpperCase().startsWith("OFF")) {
                    offCount++;
                }
                // "연", "연차", "휴가"를 포함하는 경우 연차로 간주
                else if (trimmedValue.contains("연") ||
                        trimmedValue.contains("휴가") ||
                        trimmedValue.equalsIgnoreCase("AL") ||
                        trimmedValue.equalsIgnoreCase("ANNUAL")) {
                    vacationUsed++;
                }
                else if ("반차".equals(trimmedValue) || "HD".equalsIgnoreCase(trimmedValue) ||
                        "HE".equalsIgnoreCase(trimmedValue)) {
                    vacationUsed += 0.5;
                }
            }

            // 실제 나이트 개수 설정
            entry.setNightDutyActual(nightCount);

            // 추가 나이트 개수 계산 (실제 - 의무)
            Integer required = entry.getNightDutyRequired();
            if (required == null) required = 0;

            int additional = nightCount - required;
            entry.setNightDutyAdditional(additional);

            // OFF 개수
            entry.setOffCount(offCount);

            // 이달 연차 사용 수
            entry.setVacationUsedThisMonth(vacationUsed);

            log.debug("통계 계산 완료: entryId={}, night={}, off={}, vacation={}",
                    entry.getId(), nightCount, offCount, vacationUsed);

        } catch (Exception e) {
            log.error("통계 계산 실패: entryId={}", entry.getId(), e);
        }
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

        // ✅ DRAFT 상태 + 작성자만 수정 가능
        if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
            throw new IllegalStateException("임시저장 상태에서만 수정할 수 있습니다.");
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

        for (Map.Entry<Long, Integer> entry : entryRequiredMap.entrySet()) {
            Long entryId = entry.getKey();
            Integer requiredCount = entry.getValue();

            updateNightDutyRequired(entryId, requiredCount, userId);
        }

        log.info("의무 나이트 개수 일괄 설정 완료: scheduleId={}, count={}",
                scheduleId, entryRequiredMap.size());
    }
}