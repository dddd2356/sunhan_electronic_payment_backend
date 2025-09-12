package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.dto.request.CreateContractRequestDto;
import sunhan.sunhanbackend.dto.request.UpdateFormRequestDto;
import sunhan.sunhanbackend.dto.response.ContractResponseDto;
import sunhan.sunhanbackend.dto.response.ReportsResponseDto;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.ContractStatus;
import sunhan.sunhanbackend.enums.ContractType;
import sunhan.sunhanbackend.repository.mysql.EmploymentContractRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ContractService {
    private final EmploymentContractRepository repo;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final FormService formService;
    private final UserService userService;
    private final PdfGenerationService pdfGenerationService; // 비동기 서비스 주입

    // 기존 메서드 - 모든 계약서 조회 (하위 호환성 유지)
    public Page<ContractResponseDto> getContracts(String userId, boolean isAdmin, Pageable pageable) {
        Page<EmploymentContract> page = isAdmin
                ? repo.findAllWithUsers(pageable)
                : repo.findByContractTypeWithUsers(ContractType.EMPLOYMENT_CONTRACT, pageable); // 예시
        return page.map(this::toDto);
    }

    // 새로운 메서드 - 근로계약서만 조회
    public List<ContractResponseDto> getEmploymentContracts(String userId, boolean isAdmin) {
        List<EmploymentContract> contracts;
        if (isAdmin) {
            // 모든 계약을 한 번에 조회
            contracts = repo.findAllWithUsers(Pageable.unpaged()).getContent();
            // 메모리에서 필터링
            contracts = contracts.stream()
                    .filter(c -> c.getContractType() == ContractType.EMPLOYMENT_CONTRACT)
                    .collect(Collectors.toList());
        } else {
            // 사용자 관련 모든 계약을 한 번에 조회
            contracts = repo.findContractsByCreatorOrEmployeeWithUsers(userId);
            // 메모리에서 필터링
            contracts = contracts.stream()
                    .filter(c -> c.getContractType() == ContractType.EMPLOYMENT_CONTRACT)
                    .collect(Collectors.toList());
        }
        return convertToDtoBatch(contracts);
    }

    // 근로계약서 생성 (기존 create 메서드를 타입별로 분리)
    public ContractResponseDto createEmploymentContract(String creatorId, String employeeId) {
        EmploymentContract c = new EmploymentContract();

        // ⭐️ Optional을 사용하여 사용자 정보를 안전하게 조회하고, 없으면 예외 발생
        UserEntity creator = userRepository.findByUserId(creatorId)
                .orElseThrow(() -> new EntityNotFoundException("계약서 작성자 정보를 찾을 수 없습니다. userId: " + creatorId));
        UserEntity employee = userRepository.findByUserId(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("계약 대상자 정보를 찾을 수 없습니다. userId: " + employeeId));

        c.setCreator(creator); // UserEntity 객체 설정
        c.setEmployee(employee); // UserEntity 객체 설정

        c.setContractType(ContractType.EMPLOYMENT_CONTRACT);
        c.setStatus(ContractStatus.DRAFT);
        c.setPrintable(false);

        // 근로계약서 초기 데이터 생성
        String initialFormData = getInitialFormData(ContractType.EMPLOYMENT_CONTRACT, employeeId, creatorId);
        c.setFormDataJson(initialFormData);

        repo.save(c);
        return toDto(c);
    }

    // 기존 create 메서드 유지 (하위 호환성)
    public ContractResponseDto create(String creatorId, CreateContractRequestDto req) {
        EmploymentContract c = new EmploymentContract();

        // ⭐️ Optional을 사용하여 사용자 정보를 안전하게 조회하고, 없으면 예외 발생
        UserEntity creator = userRepository.findByUserId(creatorId)
                .orElseThrow(() -> new EntityNotFoundException("계약서 작성자 정보를 찾을 수 없습니다. userId: " + creatorId));
        UserEntity employee = userRepository.findByUserId(req.getEmployeeId())
                .orElseThrow(() -> new EntityNotFoundException("계약 대상자 정보를 찾을 수 없습니다. userId: " + req.getEmployeeId()));

        c.setCreator(creator);
        c.setEmployee(employee);

        c.setContractType(req.getContractType());
        c.setStatus(ContractStatus.DRAFT);
        c.setPrintable(false);
        String initialFormData = getInitialFormData(req.getContractType(), req.getEmployeeId(), creatorId);
        c.setFormDataJson(initialFormData);

        repo.save(c);
        return toDto(c);
    }

    public ContractResponseDto getContract(Long id, String userId, boolean isAdmin) {
        EmploymentContract c = getOrThrow(id);

        // ✅ 관리자 권한이 있는 경우 바로 반환
        if (isAdmin) {
            return toDto(c);
        }

        // 기존 로직: 생성자 또는 대상자만 조회 가능
        if (!c.getCreator().getUserId().equals(userId) && !c.getEmployee().getUserId().equals(userId)) {
            throw new AccessDeniedException("권한 없음");
        }

        return toDto(c);
    }

    /**
     * 특정 직원의 근로계약서 목록을 조회합니다.
     * 프론트엔드의 "/api/v1/contract/my-status" 엔드포인트에서 사용됩니다.
     *
     * @param userId 근로계약서 대상자인 직원의 ID
     * @return 해당 직원이 대상자인 근로계약서 목록 (DTO 형태)
     */
    @Transactional(readOnly = true)
    public List<ContractResponseDto> getContractsByUserId(String userId) {
        // 🔧 JOIN FETCH를 사용하는 새로운 메서드로 교체
        List<EmploymentContract> list = repo.findByEmployeeIdAndContractTypeWithUsers(userId, ContractType.EMPLOYMENT_CONTRACT);

        // 🔧 N+1을 해결하는 배치 변환 메서드 사용 (이미 구현되어 있음)
        return convertToDtoBatch(list);
    }

    public ContractResponseDto updateForm(Long id, String userId, UpdateFormRequestDto req) throws IOException { // IOException 추가
        EmploymentContract c = getOrThrow(id);

        boolean isCreator = c.getCreator().getUserId().equals(userId);
        boolean isEmployee = c.getEmployee().getUserId().equals(userId);

        if (!isCreator && !isEmployee) {
            throw new AccessDeniedException("권한 없음");
        }

        if (c.getStatus() == ContractStatus.COMPLETED) {
            throw new AccessDeniedException("완료된 계약서는 수정할 수 없습니다.");
        }

        // 직원이 '사인 필요(SENT_TO_EMPLOYEE)' 상태에서 임시저장 또는 수정할 때
        if (isEmployee && c.getStatus() == ContractStatus.SENT_TO_EMPLOYEE) {
            ObjectNode newFormData = (ObjectNode) objectMapper.readTree(req.getFormDataJson());
            ObjectNode oldFormData = (ObjectNode) objectMapper.readTree(c.getFormDataJson());
            if (newFormData.has("signatures"))   oldFormData.set("signatures",   newFormData.get("signatures"));
            if (newFormData.has("agreements"))   oldFormData.set("agreements",   newFormData.get("agreements"));
            // 직원이 수정할 수 있는 필드 목록
            // 이 필드들의 값만 새로운 JSON에서 가져와 기존 JSON에 덮어씁니다.
            if (newFormData.has("signatures")) {
                oldFormData.set("signatures", newFormData.get("signatures"));
            }
            if (newFormData.has("agreements")) {
                oldFormData.set("agreements", newFormData.get("agreements"));
            }
            if (newFormData.has("receiptConfirmation1")) {
                oldFormData.put("receiptConfirmation1", newFormData.get("receiptConfirmation1").asText());
            }
            if (newFormData.has("receiptConfirmation2")) {
                oldFormData.put("receiptConfirmation2", newFormData.get("receiptConfirmation2").asText());
            }

            // 수정이 허용된 필드만 반영된 JSON으로 교체
            c.setFormDataJson(oldFormData.toString());

        } else if (isCreator && (c.getStatus() == ContractStatus.DRAFT || c.getStatus() == ContractStatus.RETURNED_TO_ADMIN)) {
            // 관리자는 '초안' 또는 '반려' 상태일 때만 전체 수정 가능
            c.setFormDataJson(req.getFormDataJson());
        } else {
            // 그 외의 경우 (예: 직원이 초안을 수정하려 할 때, 관리자가 직원에게 보낸 후 수정하려 할 때)
            throw new AccessDeniedException("현재 계약 상태에서는 수정할 수 없습니다.");
        }

        return toDto(repo.save(c));
    }

    public ContractResponseDto sendToEmployee(Long id, String userId) {
        EmploymentContract c = getOrThrow(id);
        if (!c.getCreator().getUserId().equals(userId))
            throw new AccessDeniedException("관리자만 전송할 수 있습니다.");
        if (c.getStatus() != ContractStatus.DRAFT && c.getStatus() != ContractStatus.RETURNED_TO_ADMIN) {
            throw new IllegalStateException("초안 또는 반려 상태에서만 전송할 수 있습니다.");
        }
        c.setStatus(ContractStatus.SENT_TO_EMPLOYEE);
        return toDto(repo.save(c));
    }

    /**
     * 직원이 서명/동의를 완료하고 승인하여 즉시 완료 처리하는 메서드
     */
    @Transactional // 트랜잭션 범위 안에서 작업
    public ContractResponseDto signByEmployee(Long id, String empId, UpdateFormRequestDto req) {
        EmploymentContract c = getOrThrow(id);

        if (!c.getEmployee().getUserId().equals(empId)) {
            log.warn("[WARN] signByEmployee 권한 불일치: contract.employeeId={}, empId={}", c.getEmployee().getUserId(), empId);
            throw new AccessDeniedException("권한 없음");
        }
        if (c.getStatus() != ContractStatus.SENT_TO_EMPLOYEE) {
            throw new IllegalStateException("직원에게 전송된 계약서만 서명 후 제출할 수 있습니다.");
        }

        // 직원이 최종 제출하는 시점의 서명/동의 데이터를 저장
        c.setFormDataJson(req.getFormDataJson());

        // ✅ PDF 생성 로직을 동기적으로 변경하여 완료 시까지 대기
        String pdfUrl = formService.generatePdf(c);

        // ✅ PDF URL과 isPrintable 상태를 즉시 업데이트
        c.setPdfUrl(pdfUrl);
        c.setPrintable(true);

        // 상태를 '완료'로 변경
        c.setStatus(ContractStatus.COMPLETED);

        log.info("계약서 ID {} 완료 처리 및 PDF 생성 완료", id);

        return toDto(repo.save(c)); // PDF URL이 포함된 상태로 응답
    }

    /**
     * 근로계약서 삭제 (작성중만 삭제가능)
     */
    @Transactional
    public void deleteEmploymentContract(Long id, String userId) {
        EmploymentContract contract = getOrThrow(id);

        // 1. Optional을 사용하여 사용자 정보를 안전하게 조회하고, 없으면 예외 발생
        UserEntity currentUser = userRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다: " + userId));


        // 권한 체크: 작성자이거나 관리자(role=2 이상)
        if (!contract.getCreator().getUserId().equals(userId)) {
            throw new AccessDeniedException("근로계약서 삭제 권한이 없습니다.");
        }

        // 작성중(DRAFT) 상태만 삭제 가능
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new IllegalStateException("작성중 상태의 근로계약서만 삭제할 수 있습니다.");
        }

        // 물리 삭제 대신 논리 삭제
        contract.setStatus(ContractStatus.DELETED);
        repo.save(contract);

        log.info("근로계약서 논리 삭제 완료: id={}, by={}", id, userId);
    }


    /**
     * 반려 처리 메서드 (반려 사유 저장)
     */
    public ContractResponseDto returnToAdmin(Long id, String empId, String reason) {
        EmploymentContract c = getOrThrow(id);
        if (!c.getEmployee().getUserId().equals(empId)) {
            throw new AccessDeniedException("직원만 반려할 수 있습니다.");
        }
        if (c.getStatus() != ContractStatus.SENT_TO_EMPLOYEE) {
            throw new IllegalStateException("전송된 계약서만 반려할 수 있습니다.");
        }

        // 반려 사유 검증
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("반려 사유는 필수입니다.");
        }

        // 반려 사유 저장 및 상태 변경
        c.setStatus(ContractStatus.RETURNED_TO_ADMIN);
        c.setRejectionReason(reason.trim());

        log.info("계약서 ID {} 반려 처리됨, 사유: {}", id, reason);
        return toDto(repo.save(c));
    }

    public ContractResponseDto approve(Long id, String empId) {
        EmploymentContract c = getOrThrow(id);
        if (!c.getEmployee().getUserId().equals(empId))
            throw new AccessDeniedException("직원만 승인할 수 있습니다.");
        if (c.getStatus() != ContractStatus.SENT_TO_EMPLOYEE) {
            throw new IllegalStateException("전송된 계약서만 승인할 수 있습니다.");
        }
        // PDF 생성 및 완료처리
        String pdf = formService.generatePdf(c);
        c.setPdfUrl(pdf);
        c.setPrintable(true);
        c.setStatus(ContractStatus.COMPLETED);
        return toDto(repo.save(c));
    }

    private String getInitialFormData(ContractType contractType, String employeeId, String creatorId) {
        // 1) 템플릿 로드
        String template = formService.getPublishedForm(contractType);

        try {
            ObjectNode formData = (ObjectNode) objectMapper.readTree(template);

            if (contractType == ContractType.EMPLOYMENT_CONTRACT) {
                // 2) Employee 정보 조회
                UserEntity emp = userRepository.findByUserId(employeeId).orElse(null);
                if (emp != null) {
                    formData.put("employeeName",   emp.getUserName());

                    // 주소와 상세 주소를 결합하여 하나의 필드에 저장
                    String fullAddress = "";
                    if (emp.getAddress() != null) {
                        fullAddress += emp.getAddress();
                    }
                    if (emp.getDetailAddress() != null && !emp.getDetailAddress().isEmpty()) {
                        if (!fullAddress.isEmpty()) {
                            fullAddress += " ";
                        }
                        fullAddress += emp.getDetailAddress();
                    }
                    formData.put("employeeAddress", fullAddress);
                    formData.put("employeePhone",  emp.getPhone());
                }

                // 3) Signature Blob → Base64 URL 주입
                if (emp != null && emp.getSignimage() != null) {
                    String base64 = Base64.getEncoder().encodeToString(emp.getSignimage());
                    String dataUrl = "data:image/png;base64," + base64;
                    formData.put("employeeSignatureUrl", dataUrl);
                }


                // 4) 직원 정보도 함께 채우고 싶다면 - Optional을 사용하여 안전하게 처리
                UserEntity creator = userRepository.findByUserId(creatorId).orElse(null);
                if (creator != null) {
                    formData.put("employerName",    creator.getUserName());
                    formData.put("employerAddress", creator.getAddress());
                    formData.put("employerPhone",   creator.getPhone());
                }
                try {
                    List<UserEntity> ceoUsers = userRepository.findByJobLevel("5");
                    UserEntity ceoUser = ceoUsers.stream().findFirst().orElse(null);

                    if (ceoUser != null) {
                        // 대표원장 이름
                        formData.put("ceoName", ceoUser.getUserName());

                        // 대표원장 서명 이미지
                        if (ceoUser.getSignimage() != null) {
                            String ceoBase64 = Base64.getEncoder().encodeToString(ceoUser.getSignimage());
                            String ceoDataUrl = "data:image/png;base64," + ceoBase64;
                            formData.put("ceoSignatureUrl", ceoDataUrl);
                        } else {
                            formData.put("ceoSignatureUrl", "");
                        }

                        log.info("대표원장 정보 조회 성공: {}", ceoUser.getUserName());
                    } else {
                        log.warn("대표원장(jobLevel=5) 사용자를 찾을 수 없습니다.");
                        formData.put("ceoName", "최철훈외 6명");
                        formData.put("ceoSignatureUrl", "");
                    }
                } catch (Exception e) {
                    log.error("대표원장 정보 조회 중 오류 발생: {}", e.getMessage(), e);
                    formData.put("ceoName", "최철훈외 6명");
                    formData.put("ceoSignatureUrl", "");
                }
            }
            return formData.toString();
        } catch (IOException e) {
            throw new RuntimeException("초기 폼 데이터 생성 실패", e);
        }
    }

    private EmploymentContract getOrThrow(Long id) {
        return repo.findWithUsersById(id)
                .orElseThrow(() -> new RuntimeException("계약서 없음"));
    }

    /**
     * 컨트롤러/폼 서비스에서 순수 JPA 엔티티가 필요할 때 사용하는 헬퍼
     */
    public EmploymentContract getContractEntity(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("EmploymentContract not found: " + id));
    }

    /**
     * 완료된 계약서 조회
     */
    @Transactional(readOnly = true)
    public List<ContractResponseDto> getCompletedContracts(String userId, boolean isAdmin) {
        List<EmploymentContract> contracts;
        if (isAdmin) {
            contracts = repo.findByStatusWithUsers(ContractStatus.COMPLETED);
        } else {
            // ✅ employee 또는 creator 둘 다 가능하게 변경
            contracts = repo.findByUserIdAndStatusWithUsers(userId, ContractStatus.COMPLETED);
        }

        return contracts.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 🔧 배치로 DTO 변환하는 헬퍼 메서드 (N+1 해결)
     */
    private List<ContractResponseDto> convertToDtoBatch(List<EmploymentContract> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return Collections.emptyList();
        }

        // 1) 먼저 엔티티에 creator/employee 이름이 이미 채워져 있는지 검사
        boolean usersLoaded = contracts.stream().allMatch(c ->
                c.getCreator() != null && c.getCreator().getUserName() != null
                        && c.getEmployee() != null && c.getEmployee().getUserName() != null);

        // final 로 선언하고 재할당하지 않음. 필요시 putAll() 로 채워넣음.
        final Map<String, UserEntity> userMap = new HashMap<>();

        if (!usersLoaded) {
            // 필요한 ID 수집
            Set<String> userIds = contracts.stream()
                    .flatMap(c -> Stream.of(
                            c.getCreator() != null ? c.getCreator().getUserId() : null,
                            c.getEmployee() != null ? c.getEmployee().getUserId() : null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (!userIds.isEmpty()) {
                List<UserEntity> users = userRepository.findByUserIdIn(userIds);
                // putAll 대신, collect 결과를 map으로 만든 뒤 putAll
                Map<String, UserEntity> fetched = users.stream()
                        .collect(Collectors.toMap(UserEntity::getUserId, Function.identity()));
                userMap.putAll(fetched);
            }
        } else {
            // 이미 로드된 사용자 엔티티들을 맵으로 모음
            for (EmploymentContract c : contracts) {
                if (c.getCreator() != null && c.getCreator().getUserId() != null) {
                    userMap.put(c.getCreator().getUserId(), c.getCreator());
                }
                if (c.getEmployee() != null && c.getEmployee().getUserId() != null) {
                    userMap.put(c.getEmployee().getUserId(), c.getEmployee());
                }
            }
        }

        // DTO 변환 — 목록 응답에서는 큰 필드(formDataJson, signimage 등)는 제외(또는 요약) 권장
        return contracts.stream()
                .map(c -> toDtoBatch(c, userMap))
                .collect(Collectors.toList());
    }

    /**
     * 🔧 사용자 맵을 사용하는 DTO 변환 메서드
     */
    private ContractResponseDto toDtoBatch(EmploymentContract c, Map<String, UserEntity> userMap) {
        ContractResponseDto dto = new ContractResponseDto();
        dto.setId(c.getId());
        dto.setCreatorId(c.getCreator() != null ? c.getCreator().getUserId() : null);
        dto.setEmployeeId(c.getEmployee() != null ? c.getEmployee().getUserId() : null);
        dto.setStatus(c.getStatus());
        dto.setContractType(c.getContractType());
        // 목록에서는 전체 formDataJson을 제외하여 페이로드 줄임
        dto.setFormDataJson(null);
        dto.setPdfUrl(c.getPdfUrl());
        dto.setPrintable(c.isPrintable());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setUpdatedAt(c.getUpdatedAt());
        dto.setRejectionReason(c.getRejectionReason());

        UserEntity creator = userMap.get(dto.getCreatorId());
        if (creator != null) dto.setCreatorName(creator.getUserName());

        UserEntity employee = userMap.get(dto.getEmployeeId());
        if (employee != null) dto.setEmployeeName(employee.getUserName());

        return dto;
    }

    // 🔧 기존 toDto 메서드는 단건 조회용으로 유지
    private ContractResponseDto toDto(EmploymentContract c) {
        ContractResponseDto dto = new ContractResponseDto();
        dto.setId(c.getId());
        dto.setCreatorId(c.getCreator().getUserId());
        dto.setEmployeeId(c.getEmployee().getUserId());
        dto.setStatus(c.getStatus());
        dto.setContractType(c.getContractType());
        dto.setFormDataJson(c.getFormDataJson());
        dto.setPdfUrl(c.getPdfUrl());
        dto.setPrintable(c.isPrintable());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setUpdatedAt(c.getUpdatedAt());
        dto.setRejectionReason(c.getRejectionReason());

        // ✅ userRepository.findByUserId() 호출 삭제
        if (c.getCreator() != null) {
            dto.setCreatorId(c.getCreator().getUserId());
            dto.setCreatorName(c.getCreator().getUserName());
        }
        if (c.getEmployee() != null) {
            dto.setEmployeeId(c.getEmployee().getUserId());
            dto.setEmployeeName(c.getEmployee().getUserName());
        }

        return dto;
    }
}