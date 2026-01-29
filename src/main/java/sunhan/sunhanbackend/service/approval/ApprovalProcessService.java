package sunhan.sunhanbackend.service.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStepHistory;
import sunhan.sunhanbackend.entity.mysql.approval.DocumentApprovalProcess;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.approval.ApprovalAction;
import sunhan.sunhanbackend.enums.approval.ApprovalProcessStatus;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalLineRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalStepHistoryRepository;
import sunhan.sunhanbackend.repository.mysql.approval.DocumentApprovalProcessRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalProcessService {

    private final DocumentApprovalProcessRepository processRepository;
    private final ApprovalStepHistoryRepository historyRepository;
    private final ApprovalLineRepository approvalLineRepository;
    private final UserRepository userRepository;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final ObjectMapper objectMapper;
    private final WorkScheduleRepository scheduleRepository;

    /**
     * 문서 제출 시 결재 프로세스 시작
     */
    @Transactional
    public DocumentApprovalProcess startApprovalProcess(
            Long documentId,
            DocumentType documentType,
            Long approvalLineId,
            String applicantId
    ) {
        ApprovalLine approvalLine = approvalLineRepository.findById(approvalLineId)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));

        LeaveApplication leaveApplication = leaveApplicationRepository.findById(documentId)
                .orElseThrow(() -> new EntityNotFoundException("문서를 찾을 수 없습니다."));

        DocumentApprovalProcess process = new DocumentApprovalProcess();
        process.setDocumentId(documentId);
        process.setDocumentType(documentType);
        process.setApprovalLine(approvalLine);
        process.setCurrentStepOrder(1); // 첫 번째 단계부터 시작
        process.setStatus(ApprovalProcessStatus.IN_PROGRESS);

        DocumentApprovalProcess saved = processRepository.save(process);

        // 첫 번째 단계의 승인자에게 알림
        notifyNextApprover(saved, approvalLine.getSteps().get(0), applicantId);

        return saved;
    }


    @Transactional
    public void approveStep(
            Long processId,
            String approverId,
            String comment,
            String signatureImageUrl,
            boolean isFinalApproval
    ) {
        DocumentApprovalProcess process = processRepository.findById(processId)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        // ✅ History에서 현재 단계 조회
        ApprovalStepHistory currentHistory = getCurrentStepHistory(process);
        validateApproverPermission(currentHistory, approverId);

        // ✅ ApprovalLine에서 step 정보 가져오기 (ApproverType 확인용)
        ApprovalStep currentStep = process.getApprovalLine().getSteps().stream()
                .filter(step -> step.getStepOrder().equals(process.getCurrentStepOrder()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("현재 단계 정보를 찾을 수 없습니다."));

        // ✅ 현재 단계의 서명 업데이트 (전결 여부와 무관하게 실행)
        updateLeaveApplicationSignature(process, currentStep, approverId, signatureImageUrl);

        // 이력 저장
        ApprovalStepHistory history = historyRepository
                .findByApprovalProcessIdAndStepOrderAndAction(
                        process.getId(),
                        process.getCurrentStepOrder(),
                        ApprovalAction.PENDING
                )
                .orElseGet(() -> {
                    ApprovalStepHistory newHistory = new ApprovalStepHistory();
                    newHistory.setApprovalProcess(process);
                    newHistory.setStepOrder(process.getCurrentStepOrder());
                    newHistory.setStepName(currentStep.getStepName());
                    newHistory.setApproverId(approverId);

                    UserEntity approver = userRepository.findByUserId(approverId)
                            .orElseThrow(() -> new EntityNotFoundException("승인자를 찾을 수 없습니다: " + approverId));

                    newHistory.setApproverName(approver.getUserName());
                    newHistory.setApproverJobLevel(approver.getJobLevel());
                    newHistory.setApproverDeptCode(approver.getDeptCode());
                    return newHistory;
                });

        history.setAction(isFinalApproval ? ApprovalAction.FINAL_APPROVED : ApprovalAction.APPROVED);
        history.setComment(comment);
        history.setSignatureImageUrl(signatureImageUrl);
        history.setIsSigned(true);
        history.setActionDate(LocalDateTime.now());

        historyRepository.save(history);

        if (isFinalApproval) {
            // ✅ 전결 처리 시에는 남은 단계만 처리
            handleFinalApproval(process, currentStep, approverId, signatureImageUrl);
            return;
        }

        moveToNextStep(process);
    }

    /**
     * ✅ 새 메서드: 휴가원 서명 데이터 업데이트
     */
    /**
     * ✅ 검토 단계는 서명 저장하지 않음
     */
    private void updateLeaveApplicationSignature(
            DocumentApprovalProcess process,
            ApprovalStep step,
            String approverId,
            String signatureImageUrl
    ) {
        if (process.getDocumentType() != DocumentType.LEAVE_APPLICATION) return;

        // ✅ 검토 단계는 서명 저장 제외
        if (Boolean.TRUE.equals(step.getIsOptional())) {
            log.info("검토 단계(isOptional=true)는 서명을 저장하지 않습니다: stepOrder={}", step.getStepOrder());
            return;
        }

        LeaveApplication application = leaveApplicationRepository.findById(process.getDocumentId())
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

        try {
            Map<String, Object> formData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(application.getFormDataJson(), new TypeReference<>() {});
            } else {
                formData = new HashMap<>();
            }

            Map<String, List<Map<String, Object>>> signatures =
                    (Map<String, List<Map<String, Object>>>) formData.getOrDefault("signatures", new HashMap<>());

            String signatureType = getSignatureType(process, step.getApproverType(), step.getStepOrder());

            log.info("서명 타입 결정: stepOrder={}, approverType={}, signatureType={}",
                    step.getStepOrder(), step.getApproverType(), signatureType);

            if (signatureType != null) {
                List<Map<String, Object>> existingSignatures = signatures.get(signatureType);
                boolean alreadySigned = existingSignatures != null &&
                        !existingSignatures.isEmpty() &&
                        Boolean.TRUE.equals(existingSignatures.get(0).get("isSigned"));

                if (alreadySigned) {
                    String existingSignerId = (String) existingSignatures.get(0).get("signerId");
                    if (approverId.equals(existingSignerId)) {
                        log.info("승인자 {}가 {}단계에서 재서명합니다.", approverId, signatureType);
                    } else {
                        log.info("{}단계는 이미 {}가 서명했으므로 {}의 서명을 건너뜁니다.",
                                signatureType, existingSignerId, approverId);
                        return;
                    }
                }

                Map<String, Object> signature = new HashMap<>();
                signature.put("text", "승인");
                signature.put("imageUrl", signatureImageUrl);
                signature.put("isSkipped", false);
                signature.put("signatureDate", LocalDateTime.now().toString());
                signature.put("signerId", approverId);
                signature.put("isSigned", true);

                UserEntity approver = userRepository.findByUserId(approverId).orElse(null);
                if (approver != null) {
                    signature.put("signerName", approver.getUserName());
                }

                signatures.put(signatureType, List.of(signature));
                formData.put("signatures", signatures);
                application.setFormDataJson(objectMapper.writeValueAsString(formData));
                leaveApplicationRepository.save(application);

                log.info("서명 저장 완료: signatureType={}, approverId={}, stepOrder={}",
                        signatureType, approverId, step.getStepOrder());
            } else {
                log.warn("서명 타입을 결정할 수 없습니다: stepOrder={}, approverType={}",
                        step.getStepOrder(), step.getApproverType());
            }
        } catch (Exception e) {
            log.error("서명 데이터 업데이트 실패", e);
        }
    }

    /**
     * ✅ 검토 단계를 제외한 실제 서명 순서로 매핑
     */
    private String getSignatureTypeFromStepOrder(DocumentApprovalProcess process, Integer stepOrder) {
        if (stepOrder == null || stepOrder <= 0) return null;

        List<ApprovalStep> allSteps = process.getApprovalLine().getSteps();

        // ✅ SUBSTITUTE와 DEPARTMENT_HEAD는 별도 처리
        for (ApprovalStep step : allSteps) {
            if (step.getStepOrder().equals(stepOrder)) {
                if (step.getApproverType() == ApproverType.SUBSTITUTE) {
                    return "substitute";
                }
                if (step.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
                    return "departmentHead";
                }
                break;
            }
        }

        // ✅ SPECIFIC_USER 중에서 isOptional=false만 필터링 (검토 단계 제외)
        List<ApprovalStep> specificUserSteps = allSteps.stream()
                .filter(step -> step.getApproverType() == ApproverType.SPECIFIC_USER
                        && !Boolean.TRUE.equals(step.getIsOptional())) // ✅ 검토 단계 제외
                .sorted(Comparator.comparing(ApprovalStep::getStepOrder))
                .collect(Collectors.toList());

        // 현재 stepOrder가 SPECIFIC_USER 중 몇 번째인지 찾기
        int specificUserIndex = -1;
        for (int i = 0; i < specificUserSteps.size(); i++) {
            if (specificUserSteps.get(i).getStepOrder().equals(stepOrder)) {
                specificUserIndex = i;
                break;
            }
        }

        if (specificUserIndex == -1) {
            return null;
        }

        // ✅ 검토 단계 제외한 순서로 매핑
        return switch (specificUserIndex) {
            case 0 -> "hrStaff";
            case 1 -> "centerDirector";
            case 2 -> "adminDirector";
            case 3 -> "ceoDirector";
            default -> null;
        };
    }

    /**
     * ✅ ApproverType과 stepOrder로 서명 타입 결정
     */
    private String getSignatureType(DocumentApprovalProcess process, ApproverType approverType, Integer stepOrder) {
        // 1. ApproverType으로 먼저 판단 (우선순위)
        if (approverType == ApproverType.SUBSTITUTE) {
            return "substitute";
        }

        if (approverType == ApproverType.DEPARTMENT_HEAD) {
            return "departmentHead";
        }

        // 2. SPECIFIC_USER인 경우 stepOrder로 판단
        if (approverType == ApproverType.SPECIFIC_USER) {
            return getSignatureTypeFromStepOrder(process, stepOrder);
        }

        return null;
    }
    /**
     * 반려 처리
     */
    @Transactional
    public void rejectStep(Long processId, String approverId, String rejectionReason) {
        DocumentApprovalProcess process = processRepository.findById(processId)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        ApprovalStepHistory currentHistory = getCurrentStepHistory(process);
        validateApproverPermission(currentHistory, approverId);

        ApprovalStep currentStep = process.getApprovalLine().getSteps().stream()
                .filter(step -> step.getStepOrder().equals(process.getCurrentStepOrder()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("현재 단계 정보를 찾을 수 없습니다."));

        // 이력 저장
        ApprovalStepHistory history = new ApprovalStepHistory();
        history.setApprovalProcess(process);
        history.setStepOrder(process.getCurrentStepOrder());
        history.setStepName(currentStep.getStepName());
        history.setApproverId(approverId);
        history.setApproverName(getUserName(approverId));
        history.setAction(ApprovalAction.REJECTED);
        history.setComment(rejectionReason);
        history.setActionDate(LocalDateTime.now());

        historyRepository.save(history);

        // 프로세스 상태 변경
        process.setStatus(ApprovalProcessStatus.REJECTED);
        processRepository.save(process);

        // 신청자에게 반려 알림
        notifyRejection(process, rejectionReason);
    }

    /**
     * 현재 단계의 승인자 확인
     */
    private ApprovalStepHistory getCurrentStepHistory(DocumentApprovalProcess process) {
        return historyRepository
                .findByApprovalProcessIdAndStepOrderAndAction(
                        process.getId(),
                        process.getCurrentStepOrder(),
                        ApprovalAction.PENDING
                )
                .orElseThrow(() -> new IllegalStateException("현재 단계를 찾을 수 없습니다."));
    }

    /**
     * 승인자 권한 검증
     */
    private void validateApproverPermission(ApprovalStepHistory history, String approverId) {
        if (history.getApproverId() == null || history.getApproverId().isEmpty()) {
            throw new IllegalStateException(
                    String.format("단계 '%s'에 승인자가 지정되지 않았습니다.", history.getStepName())
            );
        }

        if (!history.getApproverId().equals(approverId)) {
            throw new AccessDeniedException("해당 단계의 승인 권한이 없습니다.");
        }
    }

    /**
     * 다음 단계로 이동
     */
    private void moveToNextStep(DocumentApprovalProcess process) {
        List<ApprovalStep> steps = process.getApprovalLine().getSteps();
        Integer currentOrder = process.getCurrentStepOrder();

        ApprovalStep nextStep = steps.stream()
                .filter(s -> s.getStepOrder() > currentOrder)
                .min(Comparator.comparing(ApprovalStep::getStepOrder))
                .orElse(null);

        if (nextStep != null) {
            process.setCurrentStepOrder(nextStep.getStepOrder());

            if (process.getDocumentType() == DocumentType.LEAVE_APPLICATION) {
                LeaveApplication application = leaveApplicationRepository.findById(process.getDocumentId())
                        .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

                Integer nextStepOrder = nextStep.getStepOrder();

                // ✅ History에서 다음 단계의 PENDING 상태 찾기
                ApprovalStepHistory nextHistoryStep = historyRepository
                        .findByApprovalProcessIdAndStepOrderAndAction(
                                process.getId(),
                                nextStepOrder,
                                ApprovalAction.PENDING
                        )
                        .orElseThrow(() -> {
                            log.error("다음 단계 History 조회 실패: processId={}, stepOrder={}",
                                    process.getId(), nextStepOrder);

                            // ✅ 디버깅: 현재 모든 History 조회
                            List<ApprovalStepHistory> allHistories = historyRepository
                                    .findByApprovalProcessId(process.getId());
                            log.error("현재 프로세스의 모든 History: {}",
                                    allHistories.stream()
                                            .map(h -> String.format("step=%d, action=%s, approverId=%s",
                                                    h.getStepOrder(), h.getAction(), h.getApproverId()))
                                            .collect(Collectors.joining(", ")));

                            return new IllegalStateException(
                                    String.format("다음 결재 단계(%d)의 대기 중인 결재자 정보를 History에서 찾을 수 없습니다.",
                                            nextStepOrder)
                            );
                        });

                application.setCurrentApproverId(nextHistoryStep.getApproverId());
                application.setCurrentStepOrder(nextStepOrder);
                application.setCurrentApprovalStep(nextStep.getStepName());

                leaveApplicationRepository.save(application);

                log.info("다음 단계로 이동: stepOrder={}, approverId={}, stepName={}",
                        nextStepOrder, nextHistoryStep.getApproverId(), nextStep.getStepName());
            }

            if (process.getDocumentType() == DocumentType.WORK_SCHEDULE) {
                WorkSchedule schedule = scheduleRepository.findById(process.getDocumentId())
                        .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

                Integer nextStepOrder = nextStep.getStepOrder();

                ApprovalStepHistory nextHistoryStep = historyRepository
                        .findByApprovalProcessIdAndStepOrderAndAction(
                                process.getId(),
                                nextStepOrder,
                                ApprovalAction.PENDING
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                String.format("다음 결재 단계(%d)의 대기 중인 결재자 정보를 찾을 수 없습니다.", nextStepOrder)
                        ));

                // ✅ 핵심: WorkSchedule의 currentApprovalStep 업데이트
                schedule.setCurrentApprovalStep(nextStepOrder);
                scheduleRepository.save(schedule);

                log.info("근무표 다음 단계로 이동: scheduleId={}, stepOrder={}, approverId={}",
                        schedule.getId(), nextStepOrder, nextHistoryStep.getApproverId());
            }

        } else {
            // 모든 단계 완료
            process.setStatus(ApprovalProcessStatus.APPROVED);
            process.setCurrentStepOrder(null);

            if (process.getDocumentType() == DocumentType.LEAVE_APPLICATION) {
                LeaveApplication application = leaveApplicationRepository.findById(process.getDocumentId())
                        .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

                application.setStatus(LeaveApplicationStatus.APPROVED);
                application.setCurrentApproverId(null);
                application.setCurrentStepOrder(null);
                application.setCurrentApprovalStep(null);
                application.setPrintable(true);

                leaveApplicationRepository.save(application);

                log.info("결재 프로세스 완료: documentId={}", process.getDocumentId());
            }
            if (process.getDocumentType() == DocumentType.WORK_SCHEDULE) {
                WorkSchedule schedule = scheduleRepository.findById(process.getDocumentId())
                        .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

                schedule.setApprovalStatus(WorkSchedule.ScheduleStatus.APPROVED);
                schedule.setCurrentApprovalStep(0);
                schedule.setIsPrintable(true);
                scheduleRepository.save(schedule);

                log.info("근무표 결재 완료: scheduleId={}", schedule.getId());
            }
        }
    }

    /**
     * ✅ 전결 처리 - 검토 단계는 제외
     */
    private void handleFinalApproval(
            DocumentApprovalProcess process,
            ApprovalStep currentStep,
            String finalApproverId,
            String finalApproverSignatureUrl
    ) {
        // ✅ 검토 단계를 제외한 남은 단계만 처리
        List<ApprovalStep> remainingSteps = process.getApprovalLine().getSteps().stream()
                .filter(step -> step.getStepOrder() > process.getCurrentStepOrder())
                .filter(step -> step.getApproverType() == ApproverType.SPECIFIC_USER
                        && !Boolean.TRUE.equals(step.getIsOptional())) // ✅ 검토 단계 제외
                .collect(Collectors.toList());

        // ✅ 휴가원 4개 제한 체크
        if (process.getDocumentType() == DocumentType.LEAVE_APPLICATION
                && remainingSteps.size() > 4) {
            throw new IllegalStateException("휴가원의 전결 처리 가능한 단계가 4개를 초과합니다.");
        }

        for (ApprovalStep step : remainingSteps) {
            ApprovalStepHistory history = historyRepository
                    .findByApprovalProcessIdAndStepOrderAndAction(
                            process.getId(),
                            step.getStepOrder(),
                            ApprovalAction.PENDING
                    )
                    .orElse(null);

            if (history != null) {
                history.setAction(ApprovalAction.APPROVED);
                history.setComment("전결 승인으로 자동 처리됨");
                history.setIsSigned(false); // ✅ 명시적으로 false
                history.setActionDate(LocalDateTime.now());
                historyRepository.save(history);
            }

            // ✅ "전결처리!" 표시
            updateLeaveApplicationFinalApprovalSignature(process, step, finalApproverId);
        }

        process.setStatus(ApprovalProcessStatus.APPROVED);
        processRepository.save(process);
        notifyCompletion(process);
    }

    // 전결 처리된 이후 남은 단계의 서명에 "전결처리!" 표시
    private void updateLeaveApplicationFinalApprovalSignature(
            DocumentApprovalProcess process,
            ApprovalStep step,
            String finalApproverId
    ) {
        if (process.getDocumentType() != DocumentType.LEAVE_APPLICATION) return;

        LeaveApplication application = leaveApplicationRepository.findById(process.getDocumentId())
                .orElseThrow(() -> new EntityNotFoundException("휴가원을 찾을 수 없습니다."));

        try {
            Map<String, Object> formData;
            if (application.getFormDataJson() != null && !application.getFormDataJson().isEmpty()) {
                formData = objectMapper.readValue(application.getFormDataJson(), new TypeReference<>() {});
            } else {
                formData = new HashMap<>();
            }

            Map<String, List<Map<String, Object>>> signatures =
                    (Map<String, List<Map<String, Object>>>) formData.getOrDefault("signatures", new HashMap<>());

            String signatureType = getSignatureType(process, step.getApproverType(), step.getStepOrder());

            if (signatureType != null) {
                // ✅ 이미 실제 서명이 있으면 덮어쓰지 않음
                List<Map<String, Object>> existingSignatures = signatures.get(signatureType);
                boolean alreadySigned = existingSignatures != null &&
                        !existingSignatures.isEmpty() &&
                        Boolean.TRUE.equals(existingSignatures.get(0).get("isSigned"));

                if (alreadySigned) {
                    Object existingImageUrl = existingSignatures.get(0).get("imageUrl");
                    if (existingImageUrl != null && !existingImageUrl.toString().isEmpty()) {
                        log.info("{}단계는 이미 실제 서명이 있으므로 전결 표시를 하지 않습니다.", signatureType);
                        return;
                    }
                }
                String finalApprovalDateStr = application.getFinalApprovalDate() != null
                        ? application.getFinalApprovalDate().toString()
                        : LocalDateTime.now().toString();

                // ✅ "전결처리!" 표시
                Map<String, Object> signature = new HashMap<>();
                signature.put("text", "전결처리!");
                signature.put("imageUrl", null);
                signature.put("isSigned", false); // ✅ false로 명시
                signature.put("isSkipped", false);
                signature.put("isFinalApproval", true);
                signature.put("signatureDate", finalApprovalDateStr);
                signature.put("skippedBy", finalApproverId);
                signature.put("skippedReason", "전결 승인으로 생략됨");

                try {
                    UserEntity finalApprover = userRepository.findByUserId(finalApproverId).orElse(null);
                    if (finalApprover != null) {
                        signature.put("skippedByName", finalApprover.getUserName());
                        signature.put("signerId", finalApproverId);
                        signature.put("signerName", finalApprover.getUserName());
                    }
                } catch (Exception e) {
                    log.warn("전결 처리자 정보 조회 실패", e);
                }

                signatures.put(signatureType, List.of(signature));
                formData.put("signatures", signatures);
                application.setFormDataJson(objectMapper.writeValueAsString(formData));
                leaveApplicationRepository.save(application);

                log.info("전결 표시 업데이트 완료: signatureType={}, text={}, stepOrder={}",
                        signatureType, signature.get("text"), step.getStepOrder());
            }
        } catch (Exception e) {
            log.error("전결 서명 데이터 업데이트 실패", e);
        }
    }

    // 알림 관련 메서드들...
    private void notifyNextApprover(DocumentApprovalProcess process, ApprovalStep step, String applicantId) {
        // 다음 승인자 찾기 및 알림 전송
    }

    private void notifyRejection(DocumentApprovalProcess process, String reason) {
        // 반려 알림
    }

    private void notifyCompletion(DocumentApprovalProcess process) {
        // 완료 알림
    }

    private String getUserName(String userId) {
        return userRepository.findByUserId(userId)
                .map(UserEntity::getUserName)
                .orElse("Unknown");
    }

    @Transactional
    public DocumentApprovalProcess startProcessWithSteps(
            Long documentId,
            DocumentType documentType,
            ApprovalLine templateLine,
            List<ApprovalStep> steps,  // ✅ 이미 처리된 steps를 받음
            String applicantId
    ) {
        // 1. ✅ 모든 승인자의 활성 여부 검증
        validateApprovers(steps);

        // 2. DocumentApprovalProcess 엔티티 생성
        DocumentApprovalProcess process = new DocumentApprovalProcess();
        process.setDocumentId(documentId);
        process.setDocumentType(documentType);
        process.setApprovalLine(templateLine);
        process.setApplicantId(applicantId);
        process.setStatus(ApprovalProcessStatus.IN_PROGRESS);

        // 3. 첫 번째 단계 결정
        ApprovalStep firstStep = steps.stream()
                .filter(s -> s.getStepOrder() == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("결재라인에 유효한 첫 단계가 없습니다."));

        process.setCurrentStepOrder(firstStep.getStepOrder());
        DocumentApprovalProcess savedProcess = processRepository.save(process);

        // 4. ✅ 전달받은 steps를 그대로 사용 (DB 재조회 X)
        for (ApprovalStep step : steps) {
            ApprovalStepHistory history = new ApprovalStepHistory();
            history.setApprovalProcess(savedProcess);
            history.setStepOrder(step.getStepOrder());
            history.setStepName(step.getStepName());

            String approverId = step.getApproverId();

            // ✅ 로그 추가
            log.info("History 생성: stepOrder={}, approverType={}, approverId={}, stepName={}",
                    step.getStepOrder(), step.getApproverType(), approverId, step.getStepName());

            if (approverId == null || approverId.isEmpty()) {
                log.error("approverId가 null입니다! step={}, approverType={}",
                        step.getStepName(), step.getApproverType());
                throw new IllegalStateException(
                        String.format("단계 %d(%s)에 승인자가 지정되지 않았습니다.",
                                step.getStepOrder(), step.getStepName())
                );
            }

            history.setApproverId(approverId);

            UserEntity approver = userRepository.findByUserId(approverId)
                    .orElseThrow(() -> new EntityNotFoundException("승인자를 찾을 수 없습니다: " + approverId));

            history.setApproverName(approver.getUserName());
            history.setApproverJobLevel(approver.getJobLevel());
            history.setApproverDeptCode(approver.getDeptCode());

            // ✅ 모든 단계를 PENDING으로 생성
            history.setAction(ApprovalAction.PENDING);

            historyRepository.save(history);

            log.info("결재 History 생성 완료: processId={}, stepOrder={}, approverId={}, stepName={}",
                    savedProcess.getId(), step.getStepOrder(), approverId, step.getStepName());
        }

        return savedProcess;
    }

    /**
     * ✅ 승인자 활성 여부 검증
     */
    private void validateApprovers(List<ApprovalStep> steps) {
        for (ApprovalStep step : steps) {
            String approverId = step.getApproverId();
            if (approverId == null || approverId.isEmpty()) {
                throw new IllegalArgumentException(
                        "승인자 ID가 없습니다: " + step.getStepName()
                );
            }

            UserEntity approver = userRepository.findByUserId(approverId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "승인자를 찾을 수 없습니다: " + approverId
                    ));

            // ✅ 활성 여부 확인
            if (!"1".equals(approver.getUseFlag())) {
                throw new IllegalStateException(
                        String.format("승인자 '%s'(%s)는 비활성 상태입니다. 관리자에게 문의하세요.",
                                approver.getUserName(),
                                approverId)
                );
            }
        }
    }
    /**
     * 전결 처리 시, 현재 단계 이후에 남아있는 모든 결재 단계의 이름을 가져옵니다.
     *
     * @param processId 현재 결재 프로세스 ID
     * @param currentStepOrder 현재 처리된 단계의 순서
     * @return 남아있는 결재 단계의 StepName 리스트 (예: HR_STAFF_APPROVAL)
     */
    public List<String> getRemainingSteps(Long processId, Integer currentStepOrder) {
        DocumentApprovalProcess process = processRepository.findById(processId)
                .orElseThrow(() -> new EntityNotFoundException("결재 프로세스를 찾을 수 없습니다."));

        // 현재 단계보다 순서가 높은 모든 ApprovalStep을 가져옵니다.
        return process.getApprovalLine().getSteps().stream()
                .filter(step -> step.getStepOrder() > currentStepOrder)
                .map(ApprovalStep::getStepName)
                .collect(Collectors.toList());
    }
}
