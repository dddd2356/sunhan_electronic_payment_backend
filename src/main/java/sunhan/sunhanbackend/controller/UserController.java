package sunhan.sunhanbackend.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sunhan.sunhanbackend.dto.request.UpdateProfileRequestDto;
import sunhan.sunhanbackend.dto.response.DepartmentDto;
import sunhan.sunhanbackend.dto.response.UserResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.ContractService;
import sunhan.sunhanbackend.service.LeaveApplicationService;
import sunhan.sunhanbackend.service.UserService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    private final UserService userService;

    private final ContractService contractService;

    private final LeaveApplicationService leaveApplicationService;

    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String userId = (String) authentication.getPrincipal();
        UserResponseDto dto = userService.getUserWithPermissions(userId);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/role/{userId}")
    public ResponseEntity<Map<String, Object>> getUserRole(@PathVariable String userId) {
        Map<String, Object> response = new HashMap<>();
        String role = userService.getUserRole(userId);
        response.put("role", role);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userService.findAllUsers());
    }

    // 관리자만 접근 가능한 API 예시
    @GetMapping("/admin/stats")
    public ResponseEntity<String> getAdminStats() {
        return ResponseEntity.ok("관리자 통계 데이터");
    }

    // 직원 정보 조회 API
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserInfo(@PathVariable String userId, Authentication authentication) {
        try {
            String requester = extractUserIdFromAuthentication(authentication);
            UserResponseDto dto = userService.getUserResponseDto(userId, requester);
            return ResponseEntity.ok(dto);
        } catch (AccessDeniedException ade) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "NP", "message", "No Permission."));
        } catch (EntityNotFoundException enf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("getUserInfo error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/update-profile/{userId}")
    public ResponseEntity<?> updateProfile(@PathVariable String userId,
                                           @RequestBody UpdateProfileRequestDto requestDto) {
        try {
            // 사용자 인증/인가 로직 (예: JWT 토큰에서 userId를 가져와 경로 변수 userId와 일치하는지 확인)
            UserEntity updatedUser = userService.updateProfile(
                    userId,
                    requestDto.getPhone(),
                    requestDto.getAddress(),
                    requestDto.getDetailAddress(),
                    requestDto.getCurrentPassword(),
                    requestDto.getNewPassword(),
                    requestDto.getPrivacyConsent(),
                    requestDto.getNotificationConsent(),
                    requestDto.getSmsVerificationCode()
            );
            return ResponseEntity.ok(updatedUser);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic locking failure for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT) // 409 상태 코드 반환
                    .body(Map.of("message", "다른 사용자가 동시에 정보를 수정했습니다. 잠시 후 다시 시도해주세요."));
        } catch (Exception e) {
            log.error("Error updating profile for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "프로필 업데이트 중 오류가 발생했습니다."));
        }
    }

    @PostMapping("/{userId}/signature")
    public ResponseEntity<Void> uploadSignature(
            @PathVariable String userId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        userService.uploadSignature(userId, file);
        return ResponseEntity.ok().build();
    }

    /**
     * 사용자의 사인 이미지를 Base64 데이터 URL 형식으로 반환
     */
    @GetMapping("/{userId}/signature")
    public ResponseEntity<Map<String, String>> getUserSignature(@PathVariable String userId) {
        try {
            // userService.getUserSignatureAsBase64() 메서드 내부 로직 확인 필요
            // 이 메서드가 UserEntity를 조회하고 signimage를 Base64로 변환해야 함
            Map<String, String> signatureData = userService.getUserSignatureAsBase64(userId);

            if (signatureData == null || !signatureData.containsKey("signatureUrl") || signatureData.get("signatureUrl").isEmpty()) {
                log.warn("User {}'s signature was not found or is empty.", userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "서명 이미지를 찾을 수 없습니다."));
            }

            log.info("Successfully fetched signature for user {}.", userId);
            return ResponseEntity.ok(signatureData);
        } catch (IllegalArgumentException e) {
            log.error("Error fetching signature for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("An unexpected error occurred while fetching signature for user {}.", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 이미지를 가져오는 중 오류가 발생했습니다."));
        }
    }

    /**
     * 사용자의 사인 이미지를 직접 바이너리로 반환 (이미지 파일로 직접 표시하고 싶을 때)
     */
    @GetMapping(value = "/{userId}/signature/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getUserSignatureImage(@PathVariable String userId) {
        try {
            byte[] imageData = userService.getUserSignatureImage(userId);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(imageData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 현재 로그인한 사용자의 사인 이미지를 Base64로 반환
     */
    @GetMapping("/me/signature")
    public ResponseEntity<Map<String, String>> getCurrentUserSignature(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = (String) authentication.getPrincipal();
            Map<String, String> signatureData = userService.getUserSignatureAsBase64(userId);
            return ResponseEntity.ok(signatureData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 이미지를 가져오는 중 오류가 발생했습니다."));
        }
    }

    /**
     * 사용자의 서명 정보 (존재 여부, 경로 등)를 조회
     */
    @GetMapping("/{userId}/signature-info")
    public ResponseEntity<Map<String, Object>> getUserSignatureInfo(@PathVariable String userId) {
        try {
            Map<String, Object> signatureInfo = userService.getUserSignatureInfo(userId);
            return ResponseEntity.ok(signatureInfo);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "서명 이미지를 가져오는 중 오류가 발생했습니다."));
        }
    }

    /**
     * 현재 사용자의 권한 정보 조회
     */
    @GetMapping("/me/permissions")
    public ResponseEntity<?> getCurrentUserPermissions(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            // 안전한 userId 추출: principal 타입이 String 또는 다른 타입일 수 있으므로 유연하게 처리
            String userId = extractUserIdFromAuthentication(authentication);
            if (userId == null || userId.isBlank()) {
                log.warn("getCurrentUserPermissions: Unable to extract userId from authentication. principalClass={}, authName={}",
                        authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getName() : "null",
                        authentication.getName());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "인증 정보를 확인할 수 없습니다."));
            }

            // user 조회 (EntityNotFoundException 발생 가능)
            UserEntity user = userService.getUserInfo(userId);
            UserResponseDto userDto = userService.getUserWithPermissions(userId);

            // 널 안전성: role 등 필드가 널일 수 있음
            Map<String, Object> permissions = new HashMap<>();
            permissions.put("userId", Optional.ofNullable(user.getUserId()).orElse(userId));
            permissions.put("userName", user.getUserName());
            permissions.put("jobLevel", user.getJobLevel());
            permissions.put("role", user.getRole() != null ? user.getRole().toString() : null);
            permissions.put("deptCode", user.getDeptCode());
            permissions.put("isAdmin", user.isAdmin());
            permissions.put("isSuperAdmin", user.isSuperAdmin());
            permissions.put("permissions", userDto.getPermissions());

            // 관리 가능한 사용자 수: user.isAdmin()이 true일 때만 service 호출
            if (Boolean.TRUE.equals(user.isAdmin())) {
                List<UserEntity> manageableUsers = Collections.emptyList();
                try {
                    manageableUsers = userService.getManageableUsers(userId);
                } catch (Exception ex) {
                    // 관리자 목록 조회 실패는 치명적이지 않으므로 0으로 처리하되 로그 남김
                    log.warn("Failed to fetch manageable users for admin {}: {}", userId, ex.getMessage());
                }
                permissions.put("manageableUsersCount", manageableUsers != null ? manageableUsers.size() : 0);
            } else {
                permissions.put("manageableUsersCount", 0);
            }

            return ResponseEntity.ok(permissions);

        } catch (jakarta.persistence.EntityNotFoundException enf) {
            // 사용자 없음 -> 404
            log.warn("getCurrentUserPermissions - user not found: {}", enf.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "사용자를 찾을 수 없습니다."));
        } catch (NumberFormatException nfe) {
            // jobLevel 파싱 등에서 발생 가능
            log.error("getCurrentUserPermissions - number format error for user: {}, message: {}", authentication.getName(), nfe.getMessage(), nfe);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "권한 정보를 처리하는 중 오류가 발생했습니다."));
        } catch (Exception e) {
            // 상세 로그를 남기고 500 반환
            log.error("[GetMyPermissions] failed for authentication principal={}, principalClass={}",
                    authentication.getPrincipal(),
                    authentication.getPrincipal() != null ? authentication.getPrincipal().getClass().getName() : "null", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "권한 정보를 가져오는 중 오류가 발생했습니다."));
        }
    }

    private String extractUserIdFromAuthentication(Authentication authentication) {
        if (authentication == null) return null;

        Object principal = authentication.getPrincipal();
        if (principal instanceof String) {
            return (String) principal;
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            return ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        }
        if (principal instanceof org.springframework.security.oauth2.jwt.Jwt) {
            return ((org.springframework.security.oauth2.jwt.Jwt) principal).getSubject();
        }
        // fallback
        String name = authentication.getName();
        return (name != null && !name.isBlank()) ? name : null;
    }
    /**
     * 특정 사용자가 관리 가능한지 확인
     */
    @GetMapping("/can-manage/{targetUserId}")
    public ResponseEntity<?> canManageUser(
            @PathVariable String targetUserId,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = (String) authentication.getPrincipal();
            boolean canManage = userService.canManageUser(userId, targetUserId);

            Map<String, Object> response = new HashMap<>();
            response.put("canManage", canManage);
            response.put("targetUserId", targetUserId);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "권한 확인 중 오류가 발생했습니다."));
        }
    }

    /**
     * 📲 SMS 인증번호 전송
     * POST /api/v1/user/{userId}/send-verification
     */
    @PostMapping("/{userId}/send-verification")
    public ResponseEntity<Map<String, Object>> sendVerificationCode(
            @PathVariable String userId,
            @RequestParam String phone // 전송할 핸드폰 번호
    ) {
        try {
            // 인증번호 전송
            userService.sendVerificationCode(phone, userId);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "인증번호가 전송되었습니다.");
            response.put("phone", phone);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("SMS 인증번호 전송 실패: userId={}, phone={}", userId, phone, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "인증번호 전송 중 오류가 발생했습니다."));
        }
    }

    /**
     * ✅ SMS 인증번호 검증
     * POST /api/v1/user/{userId}/verify-code
     */
    @PostMapping("/{userId}/verify-code")
    public ResponseEntity<Map<String, Object>> verifySmsCode(
            @PathVariable String userId,
            @RequestParam String phone,
            @RequestParam String code
    ) {
        try {
            boolean verified = userService.verifySmsCode(phone, code);
            if (verified) {
                return ResponseEntity.ok(Map.of("message", "인증 성공"));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "인증 실패 또는 코드 만료"));
            }
        } catch (Exception e) {
            log.error("SMS 인증 검증 오류: userId={}, phone={}, code={}", userId, phone, code, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "인증 검증 중 오류가 발생했습니다."));
        }
    }

    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentDto>> getDepartments() {
        List<DepartmentDto> departments = userService.getAllDepartments();
        return ResponseEntity.ok(departments);
    }

    /**
     * [기존] 권한 체크 O - 일반 부서 조회
     */
    @GetMapping("/department/{deptCode}")
    public ResponseEntity<?> getUsersByDepartment(
            @PathVariable String deptCode,
            @RequestParam(required = false) Boolean includeSubDepts,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = (String) authentication.getPrincipal();
            List<UserEntity> users;

            if (Boolean.TRUE.equals(includeSubDepts)) {
                // ✅ 권한 체크 O
                users = userService.getActiveUsersByDeptPattern(userId, deptCode);
            } else {
                users = userService.getActiveUsersByDept(userId, deptCode);
            }

            // DTO 변환
            List<Map<String, Object>> response = users.stream()
                    .map(u -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("userId", u.getUserId());
                        map.put("userName", u.getUserName());
                        map.put("jobLevel", u.getJobLevel());
                        map.put("deptCode", u.getDeptCode());
                        map.put("phone", u.getPhone());
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("부서 직원 조회 실패: deptCode={}", deptCode, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * [신규] 권한 체크 X - 전체 부서 조회 (조직도 전용)
     */
    @GetMapping("/department/{deptCode}/all")
    public ResponseEntity<?> getAllUsersByDepartment(
            @PathVariable String deptCode,
            @RequestParam(required = false) Boolean includeSubDepts,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            List<UserEntity> users;

            if (Boolean.TRUE.equals(includeSubDepts)) {
                // ✅ 권한 체크 X
                users = userService.getAllUsersByDeptPattern(deptCode);
            } else {
                users = userRepository.findByDeptCodeAndUseFlag(deptCode, "1").stream()
                        .filter(u -> !"1".equals(u.getJobType()))
                        .collect(Collectors.toList());
            }

            // DTO 변환 (동일)
            List<Map<String, Object>> response = users.stream()
                    .map(u -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("userId", u.getUserId());
                        map.put("userName", u.getUserName());
                        map.put("jobLevel", u.getJobLevel());
                        map.put("deptCode", u.getDeptCode());
                        map.put("phone", u.getPhone());
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("전체 부서 조회 실패: deptCode={}", deptCode, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}