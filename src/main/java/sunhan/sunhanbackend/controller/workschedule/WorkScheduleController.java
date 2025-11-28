package sunhan.sunhanbackend.controller.workschedule;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleEntryRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.approval.ApprovalProcessService;
import sunhan.sunhanbackend.service.workschedule.WorkScheduleService;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule.ScheduleStatus.REVIEWED;
import static sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule.ScheduleStatus.SUBMITTED;

@RestController
@RequestMapping("/api/v1/work-schedules")
@RequiredArgsConstructor
@Slf4j
public class WorkScheduleController {

    private final ApprovalProcessService approvalProcessService;
    private final WorkScheduleService scheduleService;
    private final WorkScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final WorkScheduleEntryRepository entryRepository;
    private final DocumentApprovalProcessRepository processRepository;

    /**
     * 내 부서의 근무표 목록 조회
     */
    @GetMapping("/my-department")
    public ResponseEntity<?> getMyDepartmentSchedules(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            UserEntity user = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            List<WorkSchedule> schedules = scheduleRepository
                    .findByDeptCodeOrderByScheduleYearMonthDesc(user.getDeptCode());

            return ResponseEntity.ok(schedules);
        } catch (Exception e) {
            log.error("내 부서 근무표 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무현황표 생성
     * POST /api/v1/work-schedules
     */
    @PostMapping
    public ResponseEntity<?> createSchedule(
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            String deptCode = request.get("deptCode");
            String yearMonth = request.get("yearMonth"); // "YYYY-MM"

            WorkSchedule schedule = scheduleService.createSchedule(
                    deptCode, yearMonth, userId);

            return ResponseEntity.status(HttpStatus.CREATED).body(schedule);

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("근무표 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "근무표 생성 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무현황표 상세 조회
     * GET /api/v1/work-schedules/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getScheduleDetail(
            @PathVariable Long id,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Map<String, Object> detail = scheduleService.getScheduleDetail(id, userId);

            return ResponseEntity.ok(detail);

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("근무표 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "근무표 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무 데이터 일괄 업데이트
     * PUT /api/v1/work-schedules/{id}/work-data
     */
    @PutMapping("/{id}/work-data")
    public ResponseEntity<?> updateWorkData(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> updates =
                    (List<Map<String, Object>>) request.get("updates");

            scheduleService.updateWorkData(id, userId, updates);

            return ResponseEntity.ok(Map.of("message", "근무 데이터가 업데이트되었습니다."));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("근무 데이터 업데이트 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "근무 데이터 업데이트 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무표 제출
     * POST /api/v1/work-schedules/{id}/submit
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submitSchedule(
            @PathVariable Long id,
            @RequestBody Map<String, Long> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Long approvalLineId = request.get("approvalLineId");

            if (approvalLineId == null) {
                throw new IllegalArgumentException("결재라인을 선택해주세요.");
            }

            scheduleService.submitWithApprovalLine(id, userId, approvalLineId);
            return ResponseEntity.ok(Map.of("message", "제출되었습니다."));
        } catch (Exception e) {
            log.error("근무표 제출 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 결재 처리 (검토자/승인자 통합)
     * POST /api/v1/work-schedules/{id}/approve-step
     */
    @PostMapping("/{id}/approve-step")
    public ResponseEntity<?> approveStep(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            boolean approve = request.getOrDefault("approve", false);

            // ✅ ApprovalProcessService를 통한 처리
            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            if (!approve) {
                // 반려 처리
                approvalProcessService.rejectStep(process.getId(), userId, "반려");

                // WorkSchedule 상태도 업데이트
                WorkSchedule schedule = scheduleRepository.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.REJECTED);
                scheduleRepository.save(schedule);

                return ResponseEntity.ok(Map.of("message", "근무표가 반려되었습니다."));
            }

            // 승인 처리
            UserEntity approver = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            String signatureImageUrl = null;
            if (approver.getSignimage() != null) {
                signatureImageUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(approver.getSignimage());
            }

            approvalProcessService.approveStep(
                    process.getId(),
                    userId,
                    "승인",
                    signatureImageUrl,
                    false
            );

            return ResponseEntity.ok(Map.of("message", "결재가 완료되었습니다."));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("결재 처리 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "결재 처리 중 오류가 발생했습니다."));
        }
    }

    /**
     * 의무 나이트 개수 설정
     * PUT /api/v1/work-schedules/entries/{entryId}/night-required
     */
    @PutMapping("/entries/{entryId}/night-required")
    public ResponseEntity<?> updateNightRequired(
            @PathVariable Long entryId,
            @RequestBody Map<String, Integer> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Integer requiredCount = request.get("requiredCount");

            scheduleService.updateNightDutyRequired(entryId, requiredCount, userId);

            return ResponseEntity.ok(Map.of("message", "의무 나이트 개수가 설정되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("의무 나이트 개수 설정 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 의무 나이트 개수 일괄 설정
     * PUT /api/v1/work-schedules/{id}/night-required-batch
     */
    @PutMapping("/{id}/night-required-batch")
    public ResponseEntity<?> updateMultipleNightRequired(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            // {"123": 4, "124": 5, "125": 4} 형식
            @SuppressWarnings("unchecked")
            Map<String, Integer> entryRequiredRaw =
                    (Map<String, Integer>) request.get("entryRequired");

            Map<Long, Integer> entryRequiredMap = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> entry : entryRequiredRaw.entrySet()) {
                entryRequiredMap.put(Long.valueOf(entry.getKey()), entry.getValue());
            }

            scheduleService.updateMultipleNightDutyRequired(id, userId, entryRequiredMap);

            return ResponseEntity.ok(Map.of("message", "의무 나이트 개수가 일괄 설정되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("의무 나이트 개수 일괄 설정 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "설정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 내가 처리해야 할 근무표 목록
     * GET /api/v1/work-schedules/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingSchedules(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            // WorkScheduleRepository에 findPendingSchedulesForUser 메서드 추가 필요
            // List<WorkSchedule> schedules = scheduleRepository.findPendingSchedulesForUser(userId);

            return ResponseEntity.ok(Map.of("message", "조회 성공"));

        } catch (Exception e) {
            log.error("대기 근무표 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 엔트리 직책 변경
     */
    @PutMapping("/entries/{entryId}/position")
    public ResponseEntity<?> updateEntryPosition(
            @PathVariable Long entryId,
            @RequestBody Map<String, Long> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Long positionId = request.get("positionId");

            WorkScheduleEntry entry = entryRepository.findById(entryId)
                    .orElseThrow(() -> new EntityNotFoundException("엔트리를 찾을 수 없습니다."));

            WorkSchedule schedule = entry.getWorkSchedule();

            // ✅ 권한 검증 로직을 직접 구현
            if (schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus.APPROVED) {
                throw new IllegalStateException("승인된 근무표는 수정할 수 없습니다.");
            }

            if (!schedule.getCreatedBy().equals(userId)) {
                throw new SecurityException("근무표를 수정할 권한이 없습니다.");
            }

            entry.setPositionId(positionId);
            entryRepository.save(entry);

            return ResponseEntity.ok(Map.of("message", "직책이 변경되었습니다."));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("직책 변경 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 결재 단계 서명
     * POST /api/v1/work-schedules/{id}/sign-step
     */
    @PostMapping("/{id}/sign-step")
    public ResponseEntity<?> signStep(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Integer stepOrder = request.get("stepOrder");

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // ✅ stepOrder가 0이면 작성자 서명 (DRAFT 상태에서만)
            if (stepOrder == 0) {
                if (!schedule.getCreatedBy().equals(userId)) {
                    throw new SecurityException("작성자만 서명할 수 있습니다.");
                }

                if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
                    throw new IllegalStateException("임시저장 상태에서만 서명할 수 있습니다.");
                }

                // 작성자 서명 저장
                UserEntity creator = userRepository.findByUserId(userId)
                        .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

                if (creator.getSignimage() != null && creator.getSignimage().length > 0) {
                    String signatureUrl = "data:image/png;base64," +
                            Base64.getEncoder().encodeToString(creator.getSignimage());
                    schedule.setCreatorSignatureUrl(signatureUrl);
                    schedule.setCreatorSignedAt(LocalDateTime.now());
                    scheduleRepository.save(schedule);
                }

                return ResponseEntity.ok(Map.of("message", "작성자 서명이 완료되었습니다."));
            }

            // ✅ stepOrder가 1 이상이면 결재자 서명
            if (schedule.getApprovalStatus() != SUBMITTED) {
                throw new IllegalStateException("제출된 상태에서만 결재할 수 있습니다.");
            }

            // ApprovalProcessService를 통한 서명 처리
            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            ApprovalStep currentStep = process.getApprovalLine().getSteps().stream()
                    .filter(s -> s.getStepOrder().equals(stepOrder))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("유효하지 않은 결재 단계입니다."));

            if (!currentStep.getApproverId().equals(userId)) {
                throw new SecurityException("해당 단계의 서명 권한이 없습니다.");
            }

            UserEntity approver = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            String signatureImageUrl = null;
            if (approver.getSignimage() != null) {
                signatureImageUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(approver.getSignimage());
            }

            // ✅ ApprovalProcessService의 approveStep 호출
            approvalProcessService.approveStep(
                    process.getId(),
                    userId,
                    "서명 완료",
                    signatureImageUrl,
                    false
            );

            // ✅ WorkSchedule의 currentApprovalStep 업데이트
            schedule.setCurrentApprovalStep(process.getCurrentStepOrder());

            // ✅ 모든 단계 완료 시 APPROVED
            if (process.getStatus() == sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus.APPROVED) {
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
                schedule.setIsPrintable(true);
            }

            scheduleRepository.save(schedule);

            return ResponseEntity.ok(Map.of("message", "서명이 완료되었습니다."));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("서명 처리 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 처리 중 오류가 발생했습니다."));
        }
    }

    @PutMapping("/{id}/remarks")
    public ResponseEntity<?> updateRemarks(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String remarks = request.get("remarks");

            scheduleService.updateScheduleRemarks(id, userId, remarks);
            return ResponseEntity.ok(Map.of("message", "비고가 저장되었습니다."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pending-approvals")
    public ResponseEntity<?> getPendingApprovals(Authentication auth) {
        String userId = auth.getName();

        // ✅ 단일 status를 받는 메서드로 변경
        List<DocumentApprovalProcess> processes = processRepository
                .findByDocumentTypeAndStatus(
                        DocumentType.WORK_SCHEDULE,
                        ApprovalProcessStatus.IN_PROGRESS
                );

        List<WorkSchedule> pending = new ArrayList<>();
        for (DocumentApprovalProcess process : processes) {
            // 현재 결재자 확인
            if (process.getCurrentStepOrder() == null ||
                    process.getApprovalLine() == null ||
                    process.getApprovalLine().getSteps().isEmpty()) {
                continue;
            }

            // ✅ 배열 인덱스 에러 방지
            int currentStepIndex = process.getCurrentStepOrder() - 1;
            List<ApprovalStep> steps = process.getApprovalLine().getSteps();

            if (currentStepIndex >= 0 && currentStepIndex < steps.size()) {
                ApprovalStep currentStep = steps.get(currentStepIndex);

                if (currentStep.getApproverId() != null &&
                        currentStep.getApproverId().equals(userId)) {
                    scheduleRepository.findById(process.getDocumentId())
                            .ifPresent(pending::add);
                }
            }
        }

        return ResponseEntity.ok(pending);
    }
}