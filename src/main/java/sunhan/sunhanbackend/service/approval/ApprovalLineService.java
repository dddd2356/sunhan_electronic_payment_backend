package sunhan.sunhanbackend.service.approval;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.approval.ApprovalLineCreateDto;
import sunhan.sunhanbackend.dto.approval.ApprovalLineUpdateDto;
import sunhan.sunhanbackend.dto.approval.ApprovalStepDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalStep;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.enums.approval.ApproverType;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalLineRepository;
import sunhan.sunhanbackend.repository.mysql.approval.ApprovalStepRepository;
import sunhan.sunhanbackend.enums.approval.DocumentType;
import sunhan.sunhanbackend.service.PermissionService;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApprovalLineService {

    private final ApprovalLineRepository approvalLineRepository;
    private final ApprovalStepRepository approvalStepRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    /**
     * 결재라인 생성
     */
    @Transactional
    public ApprovalLine createApprovalLine(ApprovalLineCreateDto dto, String creatorId) {

        // 단계별 검증
        for (ApprovalStepDto stepDto : dto.getSteps()) {
            // SUBSTITUTE는 approverId 불필요
            if (stepDto.getApproverType() != ApproverType.SUBSTITUTE) {
                if (stepDto.getApproverId() == null || stepDto.getApproverId().isEmpty()) {
                    throw new IllegalArgumentException(
                            String.format("%s 단계(%s)의 승인자를 선택해주세요.",
                                    stepDto.getStepOrder(),
                                    stepDto.getStepName())
                    );
                }

                // 승인자 활성 여부 확인
                UserEntity approver = userRepository.findByUserId(stepDto.getApproverId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "승인자를 찾을 수 없습니다: " + stepDto.getApproverId()
                        ));

                if (!"1".equals(approver.getUseFlag())) {
                    throw new IllegalStateException(
                            String.format("승인자 '%s'는 비활성 상태입니다.",
                                    approver.getUserName())
                    );
                }
            }
        }

        ApprovalLine approvalLine = new ApprovalLine();
        approvalLine.setName(dto.getName());
        approvalLine.setDescription(dto.getDescription());
        approvalLine.setDocumentType(dto.getDocumentType());
        approvalLine.setCreatedBy(creatorId);
        approvalLine.setIsActive(true);

        ApprovalLine saved = approvalLineRepository.save(approvalLine);

        // 단계 추가
        for (ApprovalStepDto stepDto : dto.getSteps()) {
            ApprovalStep step = new ApprovalStep();
            step.setApprovalLine(saved);
            step.setStepOrder(stepDto.getStepOrder());
            step.setStepName(stepDto.getStepName());
            step.setApproverType(stepDto.getApproverType());
            step.setApproverId(stepDto.getApproverId());
            step.setJobLevel(stepDto.getJobLevel());
            step.setDeptCode(stepDto.getDeptCode());
            step.setIsOptional(stepDto.getIsOptional());

            approvalStepRepository.save(step);
        }

        return saved;
    }

    /**
     * 사용 가능한 결재라인 목록 조회
     */
    @Transactional(readOnly = true)
    public List<ApprovalLine> getAvailableApprovalLines(DocumentType documentType, String userId) {
        return approvalLineRepository.findByDocumentTypeAndIsActiveTrueAndIsDeletedFalseWithSteps(documentType);
    }

    /**
     * 결재라인 상세 조회
     */
    @Transactional(readOnly = true)
    public ApprovalLine getApprovalLineDetail(Long approvalLineId) {
        return approvalLineRepository.findByIdWithSteps(approvalLineId)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<ApprovalLine> getApprovalLinesByCreator(DocumentType documentType, String creatorId) {
        if (documentType == null) {
            // ✅ documentType이 null이면 전체 조회 쿼리 호출
            return approvalLineRepository.findByCreatedByAndIsActiveTrueWithSteps(creatorId);
        } else {
            // documentType이 있으면 필터링 쿼리 호출
            return approvalLineRepository.findByDocumentTypeAndCreatedByAndIsActiveTrueWithSteps(documentType, creatorId);
        }
    }

    @Transactional
    public ApprovalLine updateApprovalLine(Long id, ApprovalLineUpdateDto dto, String userId) {
        ApprovalLine existing = approvalLineRepository.findByIdWithSteps(id)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));

        // 권한검사: 작성자
        if (!userId.equals(existing.getCreatedBy())) {
            throw new SecurityException("권한이 없습니다.");
        }

        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        existing.setDocumentType(dto.getDocumentType());

        //isActive 상태 업데이트
        if (dto.getIsActive() != null) {
            existing.setIsActive(dto.getIsActive());
        }

        existing.getSteps().clear();

        // 새 단계 저장
        if (dto.getSteps() != null) {
            for (ApprovalStepDto stepDto : dto.getSteps()) {
                ApprovalStep step = new ApprovalStep();
                step.setApprovalLine(existing);
                step.setStepOrder(stepDto.getStepOrder());
                step.setStepName(stepDto.getStepName());
                step.setApproverType(stepDto.getApproverType());
                step.setApproverId(stepDto.getApproverId());
                step.setJobLevel(stepDto.getJobLevel());
                step.setDeptCode(stepDto.getDeptCode());
                step.setIsOptional(Boolean.TRUE.equals(stepDto.getIsOptional()));
                existing.getSteps().add(step);
            }
        }

        return approvalLineRepository.save(existing);
    }

    @Transactional
    public void deleteApprovalLine(Long id, String userId) {
        ApprovalLine existing = approvalLineRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));

        // 권한검사
        Optional<UserEntity> op = userRepository.findByUserId(userId);
        boolean isAdmin = op.map(u -> u.getRole() == Role.ADMIN).orElse(false);
        if (!userId.equals(existing.getCreatedBy()) && !isAdmin) {
            throw new SecurityException("권한이 없습니다.");
        }

        // ✅ 삭제 처리 (목록에서 제거)
        existing.setIsDeleted(true);
        approvalLineRepository.save(existing);
    }

    /**
     * ✅ 타입별 승인자 후보 목록 조회
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getApproverCandidates(
            ApproverType approverType,
            String applicantId,
            String requesterId
    ) {
        List<UserEntity> candidates = new ArrayList<>();

        switch (approverType) {
            case SUBSTITUTE:
                // 신청자 부서 내 사용자 (jobLevel=0)
                if (applicantId == null) {
                    throw new IllegalArgumentException("SUBSTITUTE 타입은 applicantId가 필요합니다.");
                }
                UserEntity applicant = userRepository.findByUserId(applicantId)
                        .orElseThrow(() -> new EntityNotFoundException("신청자를 찾을 수 없습니다."));

                candidates = userRepository.findByDeptCodeAndJobLevelAndUseFlag(
                                applicant.getDeptCode(),
                                "0",
                                "1"
                        ).stream()
                        .filter(u -> !u.getUserId().equals(applicantId)) // 본인 제외
                        .collect(Collectors.toList());
                break;

            case DEPARTMENT_HEAD:
                // 신청자 부서의 jobLevel=1 사용자
                if (applicantId == null) {
                    throw new IllegalArgumentException("DEPARTMENT_HEAD 타입은 applicantId가 필요합니다.");
                }
                UserEntity applicant2 = userRepository.findByUserId(applicantId)
                        .orElseThrow(() -> new EntityNotFoundException("신청자를 찾을 수 없습니다."));

                candidates = userRepository.findByDeptCodeAndJobLevelAndUseFlag(
                        applicant2.getDeptCode(),
                        "1",
                        "1"
                );
                break;

            case HR_STAFF:
                // HR_LEAVE_APPLICATION 권한 가진 사용자 (jobLevel=0 or 1)
                List<UserEntity> allUsers = userRepository.findByUseFlagAndJobLevelIn("1", Arrays.asList("0", "1"));
                candidates = allUsers.stream()
                        .filter(user -> {
                            Set<PermissionType> permissions = permissionService.getAllUserPermissions(user.getUserId());
                            return permissions.contains(PermissionType.HR_LEAVE_APPLICATION)
                                    && user.isAdmin();
                        })
                        .collect(Collectors.toList());
                break;

            case CENTER_DIRECTOR:
                // jobLevel=2 사용자
                candidates = userRepository.findByJobLevelAndUseFlag("2", "1");
                break;

            case ADMIN_DIRECTOR:
                // jobLevel=4 사용자
                candidates = userRepository.findByJobLevelAndUseFlag("4", "1");
                break;

            case CEO_DIRECTOR:
                // jobLevel=5 사용자
                candidates = userRepository.findByJobLevelAndUseFlag("5", "1");
                break;

            default:
                throw new IllegalArgumentException("지원하지 않는 ApproverType입니다: " + approverType);
        }

        // DTO 변환
        return candidates.stream()
                .map(user -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("userId", user.getUserId());
                    map.put("userName", user.getUserName());
                    map.put("jobLevel", user.getJobLevel());
                    map.put("deptCode", user.getDeptCode());
                    return map;
                })
                .collect(Collectors.toList());
    }
}