package sunhan.sunhanbackend.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.request.UpdateFormRequestDto;
import sunhan.sunhanbackend.dto.request.auth.RejectRequestDto;
import sunhan.sunhanbackend.dto.response.ContractResponseDto;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.enums.Role;
import sunhan.sunhanbackend.service.ContractService;
import sunhan.sunhanbackend.service.FormService;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.UserService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/employment-contract")
@RequiredArgsConstructor
@Slf4j
public class EmploymentContractController {
    private final ContractService service;
    private final FormService formService;
    private final UserService userService;
    private final PermissionService permissionService;

    // 근로계약서 목록 조회
    @GetMapping
    public List<ContractResponseDto> listEmploymentContracts(Authentication auth) {
        String userId = auth.getName();
        UserEntity user = userService.getUserInfo(userId);
        boolean isAdmin = (user.getRole() == Role.ADMIN)
                || Integer.parseInt(user.getJobLevel()) >= 2
                || (("0".equals(user.getJobLevel()) || "1".equals(user.getJobLevel())) &&
                permissionService.hasPermission(userId, PermissionType.HR_CONTRACT));
        return service.getEmploymentContracts(userId, isAdmin);
    }

    // 특정 근로계약서 조회
    @GetMapping("/{id}")
    public ResponseEntity<ContractResponseDto> getEmploymentContract(@PathVariable Long id, Authentication auth) {
        String userId = auth.getName();
        UserEntity me = userService.getUserInfo(userId);
        boolean isAdmin = me.isAdmin(); // 관리자 여부 확인
        ContractResponseDto dto = service.getContract(id, userId, isAdmin); // isAdmin 플래그 전달
        return ResponseEntity.ok(dto);
    }

    /**
     * 현재 로그인한 직원의 모든 근로계약서 상태를 조회
     */
    @GetMapping("/my-status")
    public ResponseEntity<List<ContractResponseDto>> getMyContractsStatus(Authentication auth) {
        String userId = auth.getName();
        // ContractService를 사용하여 특정 사용자의 계약 목록을 가져오는 로직
        List<ContractResponseDto> contractStatusList = service.getContractsByUserId(userId);
        return ResponseEntity.ok(contractStatusList);
    }

    // 새 근로계약서 생성
    @PostMapping
    public ResponseEntity<ContractResponseDto> createEmploymentContract(
            @RequestBody CreateEmploymentContractRequest req,
            Authentication auth) {
        ContractResponseDto dto = service.createEmploymentContract(auth.getName(), req.getEmployeeId());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    // 근로계약서 양식 수정
    @PutMapping("/{id}")
    public ResponseEntity<ContractResponseDto> updateForm(
            @PathVariable Long id,
            @RequestBody UpdateFormRequestDto req,
            Authentication auth) {
        try {
            String userId = (String) auth.getPrincipal();
            ContractResponseDto dto = service.updateForm(id, userId, req);
            return ResponseEntity.ok(dto);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // 직원에게 근로계약서 전송
    @PutMapping("/{id}/send")
    public ResponseEntity<ContractResponseDto> sendToEmployee(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(service.sendToEmployee(id, auth.getName()));
    }

    // 직원 서명
    @PutMapping("/{id}/sign")
    public ResponseEntity<ContractResponseDto> signByEmployee(
            @PathVariable Long id,
            @RequestBody UpdateFormRequestDto req,
            Authentication auth) {

        String authName = auth.getName();
        ContractResponseDto dto = service.signByEmployee(id, authName, req);
        return ResponseEntity.ok(dto);
    }

    /**
     * 근로계약서 삭제
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEmploymentContract(@PathVariable Long id, Authentication auth) {
        String userId = auth.getName();
        try {
            service.deleteEmploymentContract(id, userId);
            return ResponseEntity.ok(Map.of("message", "근로계약서가 성공적으로 삭제되었습니다."));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "근로계약서를 찾을 수 없습니다."));
        } catch (IllegalStateException | AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("근로계약서 삭제 실패: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "근로계약서 삭제 중 오류가 발생했습니다."));
        }
    }

    // 관리자에게 반송
    // “반려” 요청
    // 관리자에게 반송 (반려) - 수정된 부분
    @PutMapping("/{id}/return")
    public ResponseEntity<ContractResponseDto> returnToAdmin(
            @PathVariable Long id,
            @RequestBody RejectRequestDto req,
            Authentication auth) {
        return ResponseEntity.ok(service.returnToAdmin(id, auth.getName(), req.getReason()));
    }

    //직원 사인이 완료된 후 관리자에게 보내기
    @PutMapping("/{id}/approve")
    public ResponseEntity<ContractResponseDto> approve(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(service.approve(id, auth.getName()));
    }

    // 근로계약서 생성 요청 DTO
    public static class CreateEmploymentContractRequest {
        private String employeeId;

        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String employeeId) {
            this.employeeId = employeeId;
        }
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        try {
            EmploymentContract contract = service.getContractEntity(id);
            byte[] data = formService.getPdfBytes(contract);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contract_" + id + ".pdf\"")
                    .body(data);
        } catch (Exception e) {
            log.error("PDF 다운로드 실패: contractId={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(("{\"error\":\"PDF 생성에 실패했습니다: " + e.getMessage() + "\"}").getBytes());
        }
    }

    @GetMapping("/completed")
    public ResponseEntity<List<ContractResponseDto>> getCompletedContracts(Authentication auth) {
        String userId = auth.getName();
        // 예시: UserEntity를 조회해서 isAdmin() 판별
        UserEntity me = userService.getUserInfo(userId);
        boolean isAdmin = me.isAdmin();
        List<ContractResponseDto> dtos = service.getCompletedContracts(userId, isAdmin);
        return ResponseEntity.ok(dtos);
    }
}