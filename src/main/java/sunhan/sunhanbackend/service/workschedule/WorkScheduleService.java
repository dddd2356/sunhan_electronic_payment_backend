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
        // 권한 검증
        validateDeptAccess(creatorId, deptCode);

        // 중복 체크
        scheduleRepository.findByDeptCodeAndScheduleYearMonth(deptCode, yearMonth)
                .ifPresent(s -> {
                    throw new IllegalStateException("해당 년월의 근무표가 이미 존재합니다.");
                });

        WorkSchedule schedule = new WorkSchedule();
        schedule.setDeptCode(deptCode);
        schedule.setScheduleYearMonth(yearMonth);
        schedule.setCreatedBy(creatorId);
        schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.DRAFT);

        WorkSchedule saved = scheduleRepository.save(schedule);

        // 부서 직원들의 엔트리 자동 생성
        createEntriesForDeptUsers(saved);

        log.info("근무현황표 생성: id={}, dept={}, yearMonth={}",
                saved.getId(), deptCode, yearMonth);

        return saved;
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

    /**
     * 근무표 조회 (상세 정보 포함)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getScheduleDetail(Long scheduleId, String userId) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

        validateDeptAccess(userId, schedule.getDeptCode());

        List<WorkScheduleEntry> entries = entryRepository
                .findByWorkScheduleIdOrderByDisplayOrderAsc(scheduleId);

        List<Position> positions = positionRepository
                .findByDeptCodeAndIsActiveTrueOrderByDisplayOrderAsc(schedule.getDeptCode());

        Map<String, UserEntity> userMap = new HashMap<>();
        for (WorkScheduleEntry entry : entries) {
            userRepository.findByUserId(entry.getUserId())
                    .ifPresent(u -> userMap.put(u.getUserId(), u));
        }

        // ✅ 동적 결재라인 구성
        List<Map<String, Object>> approvalSteps = new ArrayList<>();

        // 1. 작성자 정보 (결재라인 존재 여부와 상관없이 항상 추가)
        UserEntity creator = userRepository.findByUserId(schedule.getCreatedBy()).orElse(null);
        if (creator != null) {
            Map<String, Object> creatorStep = new HashMap<>();
            creatorStep.put("stepName", "작성"); // 혹은 "담당"
            creatorStep.put("name", creator.getUserName());
            creatorStep.put("approverId", creator.getUserId());
            creatorStep.put("stepOrder", 0);  // ✅ 작성자는 항상 0
            creatorStep.put("signatureUrl", schedule.getCreatorSignatureUrl());
            creatorStep.put("signedAt", schedule.getCreatorSignedAt());
            creatorStep.put("isSigned", schedule.getCreatorSignatureUrl() != null);

            // ✅ DRAFT 상태이면서 아직 서명하지 않았고, 로그인한 유저가 작성자 본인일 때 서명 가능
            boolean isCreatorAndDraft = schedule.getCreatedBy().equals(userId) &&
                    schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus.DRAFT;

            creatorStep.put("isCurrent", isCreatorAndDraft && schedule.getCreatorSignatureUrl() == null);

            approvalSteps.add(creatorStep);
        }

        // 2. 결재라인의 단계 추가 (결재라인이 설정된 경우에만)
        if (schedule.getApprovalLine() != null) {
            List<ApprovalStep> steps = schedule.getApprovalLine().getSteps();
            Integer currentStep = schedule.getCurrentApprovalStep();

            for (int i = 0; i < steps.size(); i++) {
                ApprovalStep step = steps.get(i);

                Map<String, Object> stepInfo = new HashMap<>();

                // ✅ 단계 이름 동적 설정 (수정)
                String stepName;
                String currentApproverId = step.getApproverId();

                // 승인자 이름 가져오기
                String approverName = "-";
                if (currentApproverId != null) {
                    UserEntity approver = userRepository.findByUserId(currentApproverId).orElse(null);
                    if (approver != null) {
                        approverName = approver.getUserName();
                    }
                }

                // ✅ 단계 이름 결정 로직 수정
                if (step.getStepName() != null && step.getStepName().contains("부서장")) {
                    stepName = "부서장";
                } else if (step.getStepOrder() == 1) {
                    // 1번 단계 - 부서장이 0번에 있는지 확인
                    boolean hasDeptHeadBefore = steps.stream()
                            .anyMatch(s -> s.getStepOrder() < 1 &&
                                    s.getStepName() != null &&
                                    s.getStepName().contains("부서장"));
                    stepName = hasDeptHeadBefore ? "검토" : step.getStepName();
                } else if (step.getStepOrder() == steps.size() - 1) {
                    // 마지막 단계는 항상 "승인"
                    stepName = "승인";
                } else {
                    stepName = step.getStepName() != null ? step.getStepName() : "결재";
                }

                stepInfo.put("stepName", stepName);
                stepInfo.put("name", approverName);
                stepInfo.put("approverId", currentApproverId);
                stepInfo.put("stepOrder", step.getStepOrder());

                // isCurrent 로직
                stepInfo.put("isCurrent",
                        schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus.SUBMITTED &&
                                currentStep != null && currentStep.equals(step.getStepOrder())
                );

                // isSigned 로직
                stepInfo.put("isSigned", currentStep != null && currentStep > step.getStepOrder());

                // 서명 이미지 처리
                if (currentStep != null && currentStep > step.getStepOrder() && currentApproverId != null) {
                    UserEntity approver = userRepository.findByUserId(currentApproverId).orElse(null);
                    if (approver != null && approver.getSignimage() != null) {
                        stepInfo.put("signatureUrl", "data:image/png;base64," +
                                Base64.getEncoder().encodeToString(approver.getSignimage()));
                    } else {
                        stepInfo.put("signatureUrl", null);
                    }
                    String signedDate = getSignedDateFromHistory(scheduleId, step.getStepOrder());
                    stepInfo.put("signedAt", signedDate);
                } else {
                    stepInfo.put("signatureUrl", null);
                    stepInfo.put("signedAt", null);
                }

                approvalSteps.add(stepInfo);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("schedule", schedule);
        result.put("entries", entries);
        result.put("positions", positions);
        result.put("users", userMap);
        result.put("yearMonth", schedule.getScheduleYearMonth());
        result.put("daysInMonth", getDaysInMonth(schedule.getScheduleYearMonth()));
        result.put("approvalSteps", approvalSteps);

        return result;
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

    // ✅ 새 헬퍼 메서드 추가 (History에서 서명 날짜 조회)
    private String getSignedDateFromHistory(Long scheduleId, int stepOrder) {
        // DocumentApprovalProcess 조회
        DocumentApprovalProcess process = processRepository.findByDocumentIdAndDocumentType(
                scheduleId,
                sunhan.sunhanbackend.enums.approval.DocumentType.WORK_SCHEDULE
        ).orElse(null);

        if (process == null) return null;

        // ApprovalStepHistory에서 해당 단계의 서명 날짜 조회
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

        // ✅ 현재 결재 단계 확인
        if (!schedule.getCurrentApprovalStep().equals(stepOrder)) {
            throw new IllegalStateException("현재 결재 단계가 아닙니다.");
        }

        List<ApprovalStep> steps = approvalLine.getSteps();
        if (stepOrder >= steps.size()) {
            throw new IllegalStateException("유효하지 않은 결재 단계입니다.");
        }

        ApprovalStep currentStep = steps.get(stepOrder);

        // ✅ 권한 확인
        if (!currentStep.getApproverId().equals(userId)) {
            throw new SecurityException("해당 단계의 서명 권한이 없습니다.");
        }

        // ✅ 서명 완료 처리
        UserEntity approver = userRepository.findByUserId(userId)
                .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

        // ✅ 다음 단계로 이동
        schedule.setCurrentApprovalStep(stepOrder + 1);

        // ✅ 마지막 단계인 경우 승인 완료
        if (schedule.getCurrentApprovalStep() >= steps.size()) {
            schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
            schedule.setIsPrintable(true);
        }

        scheduleRepository.save(schedule);

        log.info("서명 완료: scheduleId={}, userId={}, step={}",
                scheduleId, userId, stepOrder);
    }

    /**
     * 근무 데이터 업데이트 (일괄)
     */
    @Transactional
    public void updateWorkData(Long scheduleId, String userId,
                               List<Map<String, Object>> updates) throws JsonProcessingException {
        try {
            WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // 권한 및 상태 검증
            validateScheduleEditable(schedule, userId);

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

        // 권한 검증
        validateScheduleEditable(schedule, userId);

        entry.setNightDutyRequired(requiredCount != null ? requiredCount : 0);

        // 추가 개수 재계산
        int actual = entry.getNightDutyActual() != null ? entry.getNightDutyActual() : 0;
        int required = entry.getNightDutyRequired() != null ? entry.getNightDutyRequired() : 0;
        entry.setNightDutyAdditional(actual - required);

        entryRepository.save(entry);

        log.info("의무 나이트 개수 설정: entryId={}, required={}, additional={}",
                entryId, required, entry.getNightDutyAdditional());
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