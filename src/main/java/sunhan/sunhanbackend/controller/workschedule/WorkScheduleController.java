package sunhan.sunhanbackend.controller.workschedule;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStepHistory;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleTemplate;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalStepHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleEntryRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.PdfGenerationService;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.approval.ApprovalProcessService;
import sunhan.sunhanbackend.service.workschedule.WorkScheduleService;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final PdfGenerationService pdfGenerationService;
    private final PermissionService permissionService;
    private final ApprovalStepHistoryRepository historyRepository;

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
            // ✅ 권한 확인
            Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
            boolean hasManagePermission = permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);

            List<WorkSchedule> schedules;

            if (hasManagePermission) {
                // 관리 권한: 모든 부서의 완료된 근무표
                schedules = scheduleRepository.findByApprovalStatusInOrderByScheduleYearMonthDesc(
                        Arrays.asList(WorkSchedule.ScheduleStatus.APPROVED)
                );
            } else {
                // 일반 사용자: 내 부서의 완료된 근무표 + 내가 참여한 커스텀 근무표
                schedules = scheduleRepository.findCompletedSchedulesForUser(
                        user.getDeptCode(),
                        userId,
                        WorkSchedule.ScheduleStatus.APPROVED
                );
            }

            // Map으로 변환해 creatorName 추가 (프론트 WorkSchedule 필드 맞춤)
            List<Map<String, Object>> enhancedSchedules = schedules.stream().map(s -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", s.getId());
                map.put("deptCode", s.getDeptCode());
                map.put("scheduleYearMonth", s.getScheduleYearMonth());
                map.put("createdBy", s.getCreatedBy());
                map.put("approvalStatus", s.getApprovalStatus());
                map.put("remarks", s.getRemarks());
                map.put("pdfUrl", s.getPdfUrl());
                map.put("isPrintable", s.getIsPrintable());
                map.put("createdAt", s.getCreatedAt());
                map.put("updatedAt", s.getUpdatedAt());
                List<String> memberIds = s.getEntries().stream()
                        .filter(e -> !e.getIsDeleted())
                        .map(WorkScheduleEntry::getUserId)
                        .collect(Collectors.toList());
                map.put("memberUserIds", memberIds);
                map.put("creatorSignatureUrl", s.getCreatorSignatureUrl());
                map.put("creatorSignedAt", s.getCreatorSignedAt());
                // 다른 필드 필요 시 추가 (e.g., approvalSteps)
                map.put("isCustom", s.getIsCustom());
                map.put("customDeptName", s.getCustomDeptName());
                // creatorName 조회
                String creatorName = userRepository.findByUserId(s.getCreatedBy())
                        .map(UserEntity::getUserName).orElse(s.getCreatedBy());
                map.put("creatorName", creatorName);

                return map;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(enhancedSchedules);
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
     * 작성자 서명 상태 업데이트
     * PUT /api/v1/work-schedules/{id}/creator-signature
     */
    @PutMapping("/{id}/creator-signature")
    public ResponseEntity<?> updateCreatorSignature(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || ! auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus. UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Object isSignedObj = request.get("isSigned");
            boolean isSigned = false;

            // ✅ Object를 boolean으로 안전하게 변환
            if (isSignedObj instanceof Boolean) {
                isSigned = (Boolean) isSignedObj;
            } else if (isSignedObj != null) {
                isSigned = Boolean.parseBoolean(isSignedObj.toString());
            }

            scheduleService.updateCreatorSignature(id, userId, isSigned);

            return ResponseEntity.ok(Map.of("message", "작성자 서명 상태가 업데이트되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus. FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("작성자 서명 상태 업데이트 실패", e);
            return ResponseEntity.status(HttpStatus. INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 상태 업데이트 중 오류가 발생했습니다."));
        }
    }

    /**
     * 결재 처리 (승인/반려) - 서명 이미지 포함해서 제출
     * POST /api/v1/work-schedules/{id}/approve-step
     *
     * Request Body:
     * {
     *   "approve": true/false,
     *   "rejectionReason": "반려 사유" (반려시만 필수),
     *   "stepOrder": 1 (현재 결재 단계)
     * }
     */
    @PostMapping("/{id}/approve-step")
    public ResponseEntity<? > approveStep(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || ! auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            boolean approve = Boolean.parseBoolean(request.getOrDefault("approve", false).toString());
            String rejectionReason = request.getOrDefault("rejectionReason", "반려").toString();
            Integer stepOrder = (Integer) request.get("stepOrder");

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다. "));


            // ✅ 반려 시 APPROVED 상태에서 인사팀 권한 확인
            if (!approve && schedule.getApprovalStatus() == WorkSchedule.ScheduleStatus.APPROVED) {
                Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
                if (!permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("error", "승인된 근무표를 반려할 권한이 없습니다."));
                }

                // ✅ 인사팀 반려 처리
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.REJECTED);
                schedule.setCurrentApprovalStep(0);
                schedule.setIsActive(false);
                scheduleRepository.save(schedule);

                return ResponseEntity.ok(Map.of("message", "근무표가 반려되었습니다."));
            }

            // ✅ 상태 검증
            if (schedule.getApprovalStatus() != SUBMITTED) {
                return ResponseEntity. status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "제출된 상태에서만 결재할 수 있습니다."));
            }

            if (schedule.getApprovalLine() == null ||
                    schedule.getCurrentApprovalStep() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "결재라인이 설정되지 않았습니다."));
            }

            List<ApprovalStep> steps = schedule.getApprovalLine().getSteps();
            int currentStepIndex = schedule.getCurrentApprovalStep() - 1;

            if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "유효하지 않은 결재 단계입니다. "));
            }

            ApprovalStep currentStep = steps.get(currentStepIndex);

            // ✅ 현재 단계 결재자인지 확인
            if (currentStep.getApproverId() == null ||
                    ! currentStep.getApproverId(). equals(userId)) {
                return ResponseEntity.status(HttpStatus. FORBIDDEN)
                        .body(Map.of("error", "현재 단계의 결재자가 아닙니다."));
            }

            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            if (! approve) {
                // ✅ [반려] 서명 이미지 저장하지 않고 반려만 처리
                approvalProcessService.rejectStep(process. getId(), userId, rejectionReason);
                schedule.setApprovalStatus(WorkSchedule. ScheduleStatus.REJECTED);
                // ✅ [중요] currentApprovalStep 초기화
                schedule.setCurrentApprovalStep(0);
                schedule.setIsActive(false);
                scheduleRepository.save(schedule);

                return ResponseEntity.ok(Map.of("message", "근무표가 반려되었습니다."));
            }

            // ✅ [승인] 서명 이미지 포함해서 승인 처리
            UserEntity approver = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            String signatureImageUrl = null;
            if (approver.getSignimage() != null) {
                signatureImageUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(approver.getSignimage());
            }

            // ✅ 서명 이미지를 포함하여 승인 처리
            approvalProcessService.approveStep(
                    process.getId(),
                    userId,
                    "승인",
                    signatureImageUrl,
                    false
            );

// ---------------------------
// 중요: approveStep() 호출 직후 반드시 프로세스 최신 상태를 다시 조회
// ---------------------------
            DocumentApprovalProcess updatedProcess = processRepository.findById(process.getId())
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

// WorkSchedule의 currentApprovalStep 업데이트 (process의 최신값 사용)
            schedule.setCurrentApprovalStep(updatedProcess.getCurrentStepOrder() != null ? updatedProcess.getCurrentStepOrder() : 0);

// 프로세스 상태에 따라 스케줄 상태 동기화
            if (updatedProcess.getStatus() == ApprovalProcessStatus.APPROVED) {
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
                schedule.setIsPrintable(true);
                schedule.setCurrentApprovalStep(0);
            } else if (updatedProcess.getStatus() == ApprovalProcessStatus.REJECTED) {
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.REJECTED);
                schedule.setIsActive(false);
                schedule.setCurrentApprovalStep(0);
            } else {
                // IN_PROGRESS 등: 제출된 이후 진행 중이면 REVIEWED(검토 완료)로 표시하거나 SUBMITTED 유지
                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.SUBMITTED);
            }
            scheduleRepository.save(schedule);

            return ResponseEntity.ok(Map.of("message", "결재가 완료되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus. FORBIDDEN)
                    .body(Map. of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("결재 처리 실패", e);
            return ResponseEntity. status(HttpStatus.INTERNAL_SERVER_ERROR)
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

            WorkScheduleEntry entry = entryRepository.findById(entryId).orElse(null);
            if (entry == null) {
                log.warn("직책 변경 요청된 엔트리 ID {}가 존재하지 않습니다. (이미 삭제됨)", entryId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "엔트리를 찾을 수 없습니다. (이미 삭제되었을 수 있음)"));
            }

            WorkSchedule schedule = entry.getWorkSchedule();

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
     * 결재 단계 서명 (서명 이미지만 저장)
     * POST /api/v1/work-schedules/{id}/sign-step
     */
    @PostMapping("/{id}/sign-step")
    public ResponseEntity<?> signStep(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> request,
            Authentication auth
    ) {
        if (auth == null || ! auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            Integer stepOrder = request.get("stepOrder");

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // ✅ stepOrder가 0이면 작성자 서명 (이미 구현됨 - 유지)
            if (stepOrder == 0) {
                if (! schedule.getCreatedBy().equals(userId)) {
                    throw new SecurityException("작성자만 서명할 수 있습니다.");
                }

                if (schedule.getApprovalStatus() != WorkSchedule. ScheduleStatus.DRAFT) {
                    throw new IllegalStateException("임시저장 상태에서만 서명할 수 있습니다.");
                }

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

            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            ApprovalStep currentStep = process.getApprovalLine(). getSteps().stream()
                    .filter(s -> s.getStepOrder(). equals(stepOrder))
                    . findFirst()
                    .orElseThrow(() -> new IllegalStateException("유효하지 않은 결재 단계입니다. "));

            // ✅ [핵심] 현재 단계 결재자인지 확인
            if (! currentStep.getApproverId().equals(userId)) {
                throw new SecurityException("해당 단계의 서명 권한이 없습니다.");
            }

            UserEntity approver = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            // ✅ [중요] 서명 이미지만 저장 (아직 승인하지 않음)
            if (approver.getSignimage() != null) {
                String signatureImageUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(approver.getSignimage());

                // ✅ Service 메서드: 서명 이미지 임시 저장 (DB 저장 아님)
                // 프론트엔드에서 로컬 상태로 관리하거나,
                // 또는 approval_step에 temporary_signature 칼럼 추가 필요
            }

            return ResponseEntity.ok(Map.of(
                    "message", "서명이 준비되었습니다.  승인 버튼을 눌러주세요.",
                    "stepOrder", stepOrder
            ));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus. BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map. of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("서명 처리 실패", e);
            return ResponseEntity. status(HttpStatus.INTERNAL_SERVER_ERROR)
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

    /**
     * 내가 결재해야 할 근무표 목록 (결재 대기 탭)
     * GET /api/v1/work-schedules/pending-approvals
     *
     * 조건:
     * 1. 상태가 SUBMITTED
     * 2. 현재 단계의 결재자가 나인 경우만
     * 3. 이전 단계 결재자는 제외
     */
    @GetMapping("/pending-approvals")
    public ResponseEntity<?> getPendingApprovals(Authentication auth) {
        if (auth == null || ! auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth. getName();

            // ✅ SUBMITTED 상태이고 IN_PROGRESS인 프로세스만 조회
            List<DocumentApprovalProcess> processes = processRepository
                    .findByDocumentTypeAndStatus(
                            DocumentType. WORK_SCHEDULE,
                            ApprovalProcessStatus.IN_PROGRESS
                    );

            List<WorkSchedule> pendingList = new ArrayList<>();

            for (DocumentApprovalProcess process : processes) {
                // ✅ 기본 검증
                if (process.getCurrentStepOrder() == null ||
                        process.getApprovalLine() == null ||
                        process. getApprovalLine().getSteps().isEmpty()) {
                    continue;
                }

                // ✅ 현재 단계 인덱스
                int currentStepIndex = process.getCurrentStepOrder() - 1;
                List<ApprovalStep> steps = process.getApprovalLine().getSteps();

                // ✅ 인덱스 범위 검증
                if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
                    continue;
                }

                // ✅ 현재 단계의 결재자
                ApprovalStep currentStep = steps.get(currentStepIndex);

                // ✅ [핵심] 현재 단계의 결재자가 나인 경우만
                if (currentStep.getApproverId() != null &&
                        currentStep.getApproverId().equals(userId)) {

                    WorkSchedule schedule = scheduleRepository. findById(process.getDocumentId())
                            .orElse(null);

                    if (schedule != null &&
                            schedule.getApprovalStatus() == WorkSchedule. ScheduleStatus.SUBMITTED) {
                        pendingList.add(schedule);
                    }
                }
                // ✅ 이전 단계 결재자는 제외 (조건에 안 들어감)
            }

            return ResponseEntity.ok(pendingList);

        } catch (Exception e) {
            log.error("결재 대기 근무표 조회 실패", e);
            return ResponseEntity. status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "조회 중 오류가 발생했습니다. "));
        }
    }

    /**
     * 근무표 PDF 다운로드
     * GET /api/v1/work-schedules/{id}/pdf
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<?> downloadPdf(
            @PathVariable Long id,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // ✅ 권한 확인 수정: creator, entries 참여자, WORK_SCHEDULE_MANAGE 권한
            Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
            boolean hasManagePermission = permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);
            boolean isCreator = schedule.getCreatedBy().equals(userId);
            boolean isParticipant = entryRepository.findByWorkScheduleIdAndUserId(id, userId).isPresent();

            boolean hasAccess = hasManagePermission || isCreator || isParticipant;

            if (!hasAccess || schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.APPROVED || !schedule.getIsPrintable()) {
                log.warn("PDF 접근 거부: userId={}, scheduleId={}, status={}, isPrintable={}", userId, id, schedule.getApprovalStatus(), schedule.getIsPrintable());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "PDF 다운로드 권한이 없습니다. (승인된 근무표만 가능)"));
            }

            // ✅ PDF 재생성 로직 (기존 유지, but 로그 강화)
            boolean needsRegeneration = false;

            if (schedule.getPdfUrl() == null || schedule.getPdfUrl().isEmpty()) {
                needsRegeneration = true;
            } else {
                String cleanedPath = schedule.getPdfUrl().replaceFirst("^/+uploads/?", "").trim();
                Path uploadsRoot = Paths.get("C:", "sunhan_electronic_payment").toAbsolutePath().normalize();
                Path pdfPath = uploadsRoot.resolve(cleanedPath).normalize();

                if (!Files.exists(pdfPath) || Files.size(pdfPath) == 0) {
                    needsRegeneration = true;
                    schedule.setPdfUrl(null);
                    scheduleRepository.save(schedule);
                }
            }

            if (needsRegeneration) {
                log.info("PDF 생성 시작: scheduleId={}", id);
                pdfGenerationService.generateWorkSchedulePdfAsync(id);
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of("status", "generating", "message", "PDF 생성 중입니다. 잠시 후 다시 시도해주세요."));
            }

            // ✅ PDF 파일 로드 (기존 유지)
            String pdfUrl = schedule.getPdfUrl();
            String cleanedPath = pdfUrl.replaceFirst("^/+uploads/?", "").trim();
            Path uploadsRoot = Paths.get("C:", "sunhan_electronic_payment").toAbsolutePath().normalize();
            Path pdfPath = uploadsRoot.resolve(cleanedPath).normalize();

            byte[] pdfBytes = Files.readAllBytes(pdfPath);

            String safeFilename = String.format("work_schedule_%s_%s.pdf",
                    schedule.getDeptCode(),
                    schedule.getScheduleYearMonth().replace("-", ""));

            String encodedFilename = URLEncoder.encode(safeFilename, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentLength(pdfBytes.length);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeFilename + "\"; filename*=UTF-8''" + encodedFilename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);

        } catch (IOException e) {
            log.error("PDF 파일 읽기 실패: scheduleId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF 파일 읽기 실패"));
        } catch (Exception e) {
            log.error("근무표 PDF 다운로드 실패: scheduleId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF 다운로드 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무표 PDF 초기화 (수정 시 기존 PDF 무효화)
     * DELETE /api/v1/work-schedules/{id}/pdf
     */
    @DeleteMapping("/{id}/pdf")
    public ResponseEntity<?> deletePdf(
            @PathVariable Long id,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // 변경: 작성자 이거나 OR 관리 권한(WORK_SCHEDULE_MANAGE)이 있는 경우 허용
            Set<PermissionType> permissions = permissionService.getAllUserPermissions(userId);
            boolean hasManagePermission = permissions.contains(PermissionType.WORK_SCHEDULE_MANAGE);
            boolean isCreator = schedule.getCreatedBy().equals(userId);

            if (!isCreator && !hasManagePermission) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "근무표를 수정할 권한이 없습니다."));
            }

            // ✅ 기존 PDF 파일 삭제
            if (schedule.getPdfUrl() != null && !schedule.getPdfUrl().isEmpty()) {
                String oldPath = schedule.getPdfUrl().replaceFirst("^/+uploads/?", "").trim();
                Path uploadsRoot = Paths.get("C:", "sunhan_electronic_payment").toAbsolutePath().normalize();
                Path oldFile = uploadsRoot.resolve(oldPath).normalize();

                try {
                    if (Files.exists(oldFile)) {
                        Files.delete(oldFile);
                        log.info("기존 PDF 삭제 완료: {}", oldFile);
                    }
                } catch (IOException e) {
                    log.warn("기존 PDF 삭제 실패: {}", oldFile, e);
                }
            }

            // ✅ DB에서 PDF URL 제거
            schedule.setPdfUrl(null);
            scheduleRepository.save(schedule);

            return ResponseEntity.ok(Map.of("message", "PDF가 초기화되었습니다."));

        } catch (Exception e) {
            log.error("PDF 삭제 실패: scheduleId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF 삭제 중 오류가 발생했습니다."));
        }
    }

    // 1. 커스텀 근무표 생성
    @PostMapping("/custom")
    public ResponseEntity<?> createCustomSchedule(
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String yearMonth = (String) request.get("yearMonth");
            String customDeptName = (String) request.get("customDeptName");

            @SuppressWarnings("unchecked")
            List<String> memberUserIds = (List<String>) request.get("memberUserIds");

            WorkSchedule schedule = scheduleService.createCustomSchedule(
                    yearMonth, userId, customDeptName, memberUserIds);

            return ResponseEntity.status(HttpStatus.CREATED).body(schedule);

        } catch (Exception e) {
            log.error("커스텀 근무표 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 2. 템플릿 저장
    @PostMapping("/templates")
    public ResponseEntity<?> saveTemplate(
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String templateName = (String) request.get("templateName");
            String customDeptName = (String) request.get("customDeptName");

            @SuppressWarnings("unchecked")
            List<String> memberUserIds = (List<String>) request.get("memberUserIds");

            WorkScheduleTemplate template = scheduleService.saveTemplate(
                    userId, templateName, customDeptName, memberUserIds);

            return ResponseEntity.ok(template);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 3. 템플릿 목록 조회
    @GetMapping("/templates")
    public ResponseEntity<?> getMyTemplates(Authentication auth) {
        try {
            String userId = auth.getName();
            List<WorkScheduleTemplate> templates = scheduleService.getMyTemplates(userId);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 4. 템플릿에서 생성
    @PostMapping("/from-template/{templateId}")
    public ResponseEntity<?> createFromTemplate(
            @PathVariable Long templateId,
            @RequestBody Map<String, String> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String yearMonth = request.get("yearMonth");

            WorkSchedule schedule = scheduleService.createScheduleFromTemplate(
                    templateId, yearMonth, userId);

            return ResponseEntity.status(HttpStatus.CREATED).body(schedule);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 5. 엔트리 추가
    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMembers(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();

            @SuppressWarnings("unchecked")
            List<String> userIds = (List<String>) request.get("userIds");

            scheduleService.addMembersToSchedule(id, userIds, userId);

            return ResponseEntity.ok(Map.of("message", "인원이 추가되었습니다."));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // 6. 엔트리 삭제
    @DeleteMapping("/{id}/members")
    public ResponseEntity<?> removeMembers(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();

            @SuppressWarnings("unchecked")
            List<Integer> entryIdsInt = (List<Integer>) request.get("entryIds");
            List<Long> entryIds = entryIdsInt.stream()
                    .map(Long::valueOf)
                    .collect(Collectors.toList());

            scheduleService.removeMembersFromSchedule(id, entryIds, userId);

            return ResponseEntity.ok(Map.of("message", "인원이 삭제되었습니다."));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 내 근무표 현황 조회 (MainPage용)
     * GET /api/v1/work-schedules/my-status
     */
    @GetMapping("/my-status")
    public ResponseEntity<?> getMyWorkScheduleStatus(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            // ✅ administrator 계정 특별 처리
            if ("administrator".equalsIgnoreCase(userId)) {
                log.info("Administrator 계정 - 근무표 없음");
                return ResponseEntity.ok(Collections.emptyList());
            }

            UserEntity user = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            // ✅ deptCode null 체크
            if (user.getDeptCode() == null || user.getDeptCode().isEmpty()) {
                log.warn("사용자 {}의 부서 코드가 없음", userId);
                return ResponseEntity.ok(Collections.emptyList());
            }

            // 내가 속한 부서의 최근 근무표 조회
            List<WorkSchedule> mySchedules = scheduleRepository
                    .findByDeptCodeOrderByScheduleYearMonthDesc(user.getDeptCode())
                    .stream()
                    .limit(3)
                    .collect(Collectors.toList());

            // WorkScheduleStatus 형태로 변환
            List<Map<String, Object>> statusList = mySchedules.stream()
                    .map(schedule -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", schedule.getId());
                        map.put("title", schedule.getScheduleYearMonth() + " 근무표");
                        map.put("status", schedule.getApprovalStatus().toString());
                        map.put("createdAt", schedule.getCreatedAt());
                        map.put("updatedAt", schedule.getUpdatedAt());
                        map.put("scheduleYearMonth", schedule.getScheduleYearMonth());
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(statusList);

        } catch (Exception e) {
            log.error("근무표 현황 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "근무표 현황 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 근무표 삭제 (DRAFT 상태만)
     * DELETE /api/v1/work-schedules/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSchedule(
            @PathVariable Long id,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // ✅ DRAFT 상태이고 작성자만 삭제 가능
            if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "임시저장 상태만 삭제할 수 있습니다."));
            }

            if (!schedule.getCreatedBy().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "작성자만 삭제할 수 있습니다."));
            }

            scheduleRepository.delete(schedule);
            return ResponseEntity.ok(Map.of("message", "근무표가 삭제되었습니다."));

        } catch (Exception e) {
            log.error("근무표 삭제 실패: scheduleId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "삭제 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ 전결 권한 확인
     */
    @GetMapping("/{id}/can-final-approve")
    public ResponseEntity<?> canFinalApprove(
            @PathVariable Long id,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // 제출된 상태가 아니면 전결 불가
            if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.SUBMITTED) {
                return ResponseEntity.ok(Map.of("canFinalApprove", false));
            }

            // 현재 승인 단계 확인
            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            ApprovalStep currentStep = process.getApprovalLine().getSteps().stream()
                    .filter(s -> s.getStepOrder().equals(process.getCurrentStepOrder()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("현재 단계를 찾을 수 없습니다."));

            // 현재 승인자가 맞는지 확인
            if (!userId.equals(currentStep.getApproverId())) {
                return ResponseEntity.ok(Map.of("canFinalApprove", false));
            }

            // 전결 권한 확인
            boolean canFinalApprove = permissionService.getAllUserPermissions(userId)
                    .stream()
                    .anyMatch(p ->
                            p == PermissionType.FINAL_APPROVAL_ALL ||
                                    p == PermissionType.FINAL_APPROVAL_WORK_SCHEDULE
                    );

            return ResponseEntity.ok(Map.of("canFinalApprove", canFinalApprove));

        } catch (Exception e) {
            log.error("전결 권한 확인 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ✅ 전결 승인 처리
     */
    @PostMapping("/{id}/final-approve")
    public ResponseEntity<?> finalApprove(
            @PathVariable Long id,
            @RequestBody Map<String, Integer> payload,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            Integer stepOrder = payload.get("stepOrder");

            // 전결 권한 확인
            boolean canFinalApprove = permissionService.getAllUserPermissions(userId)
                    .stream()
                    .anyMatch(p ->
                            p == PermissionType.FINAL_APPROVAL_ALL ||
                                    p == PermissionType.FINAL_APPROVAL_WORK_SCHEDULE
                    );

            if (!canFinalApprove) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "전결 승인 권한이 없습니다."));
            }

            // 서명 이미지 가져오기
            UserEntity approver = userRepository.findByUserId(userId)
                    .orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            String signatureImageUrl = null;
            if (approver.getSignimage() != null) {
                signatureImageUrl = "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(approver.getSignimage());
            }

            // 전결 승인 처리
            DocumentApprovalProcess process = processRepository
                    .findByDocumentIdAndDocumentType(id, DocumentType.WORK_SCHEDULE)
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            // ✅ [중요] 현재 단계 서명 저장
            approvalProcessService.approveStep(
                    process.getId(),
                    userId,
                    "전결 승인",
                    signatureImageUrl,
                    true   // isFinalApproval = true
            );

            // ✅ approveStep 후 process 재조회
            process = processRepository.findById(process.getId())
                    .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

            // ✅ 현재 단계 history 조회
            List<ApprovalStepHistory> histories = historyRepository
                    .findByApprovalProcessIdAndStepOrderAndActionIn(
                            process.getId(),
                            stepOrder,
                            List.of(ApprovalAction.APPROVED, ApprovalAction.FINAL_APPROVED)
                    );

            ApprovalStepHistory currentHistory = histories.stream()
                    .max(Comparator.comparing(ApprovalStepHistory::getActionDate))
                    .orElse(null);

            if (currentHistory == null) {
                log.warn("현재 단계 history 없음: processId={}, stepOrder={}", process.getId(), stepOrder);
            }

            // ✅ [수정] 이후 모든 단계를 "전결처리" 상태로 저장 (서명 이미지 없이!)
            List<ApprovalStep> steps = process.getApprovalLine().getSteps();
            for (int i = stepOrder; i < steps.size(); i++) {
                ApprovalStep step = steps.get(i);

                // 현재 단계는 이미 처리했으므로 스킵
                if (step.getStepOrder().equals(stepOrder)) continue;

                // 이후 단계들을 "전결처리" 상태로 저장
                ApprovalStepHistory skipHistory = new ApprovalStepHistory();
                skipHistory.setApprovalProcess(process);
                skipHistory.setStepOrder(step.getStepOrder());
                skipHistory.setStepName(step.getStepName());
                skipHistory.setApproverId(step.getApproverId());

                // 결재자 이름 조회
                UserEntity nextApprover = userRepository.findByUserId(step.getApproverId())
                        .orElse(null);
                if (nextApprover != null) {
                    skipHistory.setApproverName(nextApprover.getUserName());
                }

                skipHistory.setAction(ApprovalAction.FINAL_APPROVED);
                skipHistory.setActionDate(LocalDateTime.now());
                skipHistory.setComment("전결 처리 by " + approver.getUserName());

                // ✅ [핵심 수정] 서명 이미지를 NULL로 설정 (전결처리 텍스트만 표시하도록)
                skipHistory.setSignatureImageUrl(null);

                historyRepository.save(skipHistory);
            }

            // WorkSchedule 상태 업데이트
            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
            schedule.setCurrentApprovalStep(0);
            schedule.setIsPrintable(true);
            scheduleRepository.save(schedule);

            return ResponseEntity.ok(Map.of("message", "전결 승인 완료"));

        } catch (Exception e) {
            log.error("전결 승인 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 특정 달 데이터 불러오기
     */
    @PostMapping("/{id}/copy-from")
    public ResponseEntity<?> copyFromSpecificMonth(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            Authentication auth
    ) {
        try {
            String userId = auth.getName();
            String sourceYearMonth = payload.get("sourceYearMonth");

            if (sourceYearMonth == null || !sourceYearMonth.matches("\\d{4}-\\d{2}")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "유효한 sourceYearMonth(YYYY-MM)를 입력하세요."));
            }

            WorkSchedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            if (!schedule.getCreatedBy().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "작성자만 데이터를 불러올 수 있습니다."));
            }

            if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "임시저장 상태에서만 데이터를 불러올 수 있습니다."));
            }

            scheduleService.copyFromSpecificMonth(id, sourceYearMonth);
            return ResponseEntity.ok(Map.of("message", "데이터 불러오기 완료: " + sourceYearMonth));

        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("데이터 불러오기 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서버 오류가 발생했습니다."));
        }
    }

    /**
     * 내가 작성한 근무표 목록 (DRAFT, SUBMITTED, REJECTED 상태만)
     * GET /api/v1/work-schedules/my-documents
     */
    @GetMapping("/my-documents")
    public ResponseEntity<?> getMyDocuments(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            // DRAFT, SUBMITTED, REJECTED 상태의 내 작성 문서만 조회
            List<WorkSchedule> schedules = scheduleRepository
                    .findByCreatedByAndApprovalStatusInOrderByScheduleYearMonthDesc(
                            userId,
                            Arrays.asList(
                                    WorkSchedule.ScheduleStatus.DRAFT,
                                    WorkSchedule.ScheduleStatus.SUBMITTED,
                                    WorkSchedule.ScheduleStatus.REJECTED
                            )
                    );

            // creatorName 추가하여 반환
            List<Map<String, Object>> enhancedSchedules = schedules.stream().map(s -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", s.getId());
                map.put("deptCode", s.getDeptCode());
                map.put("scheduleYearMonth", s.getScheduleYearMonth());
                map.put("createdBy", s.getCreatedBy());
                map.put("approvalStatus", s.getApprovalStatus());
                map.put("remarks", s.getRemarks());
                map.put("createdAt", s.getCreatedAt());
                map.put("updatedAt", s.getUpdatedAt());
                map.put("isCustom", s.getIsCustom());
                map.put("customDeptName", s.getCustomDeptName());

                String creatorName = userRepository.findByUserId(s.getCreatedBy())
                        .map(UserEntity::getUserName).orElse(s.getCreatedBy());
                map.put("creatorName", creatorName);

                return map;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(enhancedSchedules);

        } catch (Exception e) {
            log.error("내 작성 문서 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "조회 중 오류가 발생했습니다."));
        }
    }
}