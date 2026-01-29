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

        // ✅ 휴가원인 경우 결재 단계(SPECIFIC_USER + !isOptional) 개수 체크
        if (dto.getDocumentType() == DocumentType.LEAVE_APPLICATION) {
            long approvalStepCount = dto.getSteps().stream()
                    .filter(step -> step.getApproverType() == ApproverType.SPECIFIC_USER
                            && !Boolean.TRUE.equals(step.getIsOptional()))
                    .count();

            if (approvalStepCount > 4) {
                throw new IllegalArgumentException(
                        "휴가원의 결재 단계는 최대 4개까지만 가능합니다. (검토 단계 제외)"
                );
            }
        }

        // 단계별 검증
        for (ApprovalStepDto stepDto : dto.getSteps()) {
            // ✅ SUBSTITUTE, DEPARTMENT_HEAD는 approverId 불필요
            if (stepDto.getApproverType() == ApproverType.SUBSTITUTE
                    || stepDto.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
                // approverId 검증 생략
                continue;
            }

            // ✅ SPECIFIC_USER는 !isOptional일 때만 필수
            if (stepDto.getApproverType() == ApproverType.SPECIFIC_USER) {
                if (!Boolean.TRUE.equals(stepDto.getIsOptional())) {
                    if (stepDto.getApproverId() == null || stepDto.getApproverId().isEmpty()) {
                        throw new IllegalArgumentException(
                                String.format("%s 단계(%s)의 승인자를 선택해주세요.",
                                        stepDto.getStepOrder(), stepDto.getStepName())
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
        ApprovalLine approvalLine = approvalLineRepository.findByIdWithSteps(approvalLineId)
                .orElseThrow(() -> new EntityNotFoundException("결재라인을 찾을 수 없습니다."));

        // ✅ 각 단계의 승인자 이름 조회 및 설정
        for (ApprovalStep step : approvalLine.getSteps()) {
            if (step.getApproverType() == ApproverType.SPECIFIC_USER
                    && step.getApproverId() != null
                    && !step.getApproverId().isEmpty()) {

                userRepository.findByUserId(step.getApproverId())
                        .ifPresent(user -> {
                            // Entity에 approverName 필드가 없으므로
                            // 이 정보는 Controller에서 DTO 변환 시 처리해야 함
                        });
            }
        }

        return approvalLine;
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


        // ✅ 휴가원 결재 단계 개수 체크 추가
        if (dto.getDocumentType() == DocumentType.LEAVE_APPLICATION) {
            long approvalStepCount = dto.getSteps().stream()
                    .filter(step -> step.getApproverType() == ApproverType.SPECIFIC_USER
                            && !Boolean.TRUE.equals(step.getIsOptional()))
                    .count();

            if (approvalStepCount > 4) {
                throw new IllegalArgumentException(
                        "휴가원의 결재 단계는 최대 4개까지만 가능합니다. (검토 단계 제외)"
                );
            }
        }

        // ✅ 단계별 검증 로직 추가 (createApprovalLine과 동일)
        if (dto.getSteps() != null) {
            for (ApprovalStepDto stepDto : dto.getSteps()) {
                if (stepDto.getApproverType() == ApproverType.SUBSTITUTE
                        || stepDto.getApproverType() == ApproverType.DEPARTMENT_HEAD) {
                    continue;
                }

                if (stepDto.getApproverType() == ApproverType.SPECIFIC_USER) {
                    if (!Boolean.TRUE.equals(stepDto.getIsOptional())) {
                        if (stepDto.getApproverId() == null || stepDto.getApproverId().isEmpty()) {
                            throw new IllegalArgumentException(
                                    String.format("%s 단계(%s)의 승인자를 선택해주세요.",
                                            stepDto.getStepOrder(), stepDto.getStepName())
                            );
                        }

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
            }
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

}