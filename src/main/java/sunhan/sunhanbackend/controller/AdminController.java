package sunhan.sunhanbackend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.request.UpdateUserFlagRequestDto;
import sunhan.sunhanbackend.dto.request.permissions.GrantRoleByConditionDto;
import sunhan.sunhanbackend.dto.request.permissions.GrantRoleByUserIdDto;
import sunhan.sunhanbackend.dto.request.permissions.UpdateJobLevelRequestDto;
import sunhan.sunhanbackend.dto.response.UserResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.UserService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController  // Controller 클래스에 @RestController 추가
//@RequiredArgsConstructor  // 생성자 주입을 위한 어노테이션 추가
@RequestMapping("/api/v1/admin")
@Slf4j
public class AdminController {
    private final UserService userService;  // UserService 주입
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;  // JwtProvider 변수 선언
    private final PermissionService permissionService;

    @Autowired
    public AdminController(UserService userService, UserRepository userRepository, JwtProvider jwtProvider, PermissionService permissionService) {
        this.userService = userService;
        this.userRepository = userRepository;  // 생성자 주입
        this.jwtProvider = jwtProvider;
        this.permissionService = permissionService;
    }

    /**
     * 모든 사용자 조회 (기존)
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponseDto>> getAllUsers() {
        List<UserEntity> users = userService.findAllUsers();
        List<UserResponseDto> dtos = users.stream().map(u -> {
            UserResponseDto dto = new UserResponseDto();
            dto.setUserId(u.getUserId());
            dto.setUserName(u.getUserName());
            dto.setDeptCode(u.getDeptCode()); // 필요하면 가공
            dto.setJobLevel(u.getJobLevel());
            dto.setRole(u.getRole() == null ? null : u.getRole().toString());
            dto.setUseFlag(u.getUseFlag());
            // Department 엔티티 필드는 포함하지 않음
            return dto;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * 관리 가능한 사용자 목록 조회
     */
    @GetMapping("/manageable-users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getManageableUsers(Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            List<UserEntity> users = userService.getManageableUsers(adminUserId);
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서별 직원 조회
     */
    @GetMapping("/users/department/{deptCode}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('ROLE_DEPT_APPROVER')")
    public ResponseEntity<?> getUsersByDepartment(
            @PathVariable String deptCode,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            List<UserEntity> users = userService.getUsersByDeptForAdmin(adminUserId, deptCode);
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 개별 사용자에게 ADMIN 권한 부여
     */
    @PostMapping("/grant-admin-role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> grantAdminRole(
            @RequestBody GrantRoleByUserIdDto request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            userService.grantAdminRole(adminUserId, request.getTargetUserId());

            Map<String, String> response = new HashMap<>();
            response.put("message", "ADMIN 권한 부여 완료");
            response.put("targetUserId", request.getTargetUserId());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ADMIN 권한 제거 (USER로 변경)
     */
    @PostMapping("/revoke-admin-role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> revokeAdminRole(
            @RequestBody GrantRoleByUserIdDto request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            userService.revokeAdminRole(adminUserId, request.getTargetUserId());

            Map<String, String> response = new HashMap<>();
            response.put("message", "ADMIN 권한 제거 완료");
            response.put("targetUserId", request.getTargetUserId());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * JobLevel + DeptCode 조건으로 ADMIN 권한 부여
     */
    @PostMapping("/grant-admin-role-by-condition")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> grantAdminRoleByCondition(
            @RequestBody GrantRoleByConditionDto request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            userService.grantAdminRoleByCondition(
                    adminUserId,
                    request.getJobLevel(),
                    request.getDeptCode()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("message", "조건별 ADMIN 권한 부여 완료");
            response.put("jobLevel", request.getJobLevel());
            response.put("deptCode", request.getDeptCode());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * JobLevel 변경
     */
    @PutMapping("/update-job-level")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateJobLevel(
            @RequestBody UpdateJobLevelRequestDto request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            userService.updateJobLevel(
                    adminUserId,
                    request.getTargetUserId(),
                    request.getNewJobLevel()
            );

            Map<String, String> response = new HashMap<>();
            response.put("message", "JobLevel 변경 완료");
            response.put("targetUserId", request.getTargetUserId());
            response.put("newJobLevel", request.getNewJobLevel());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 사용자 관리 가능 여부 확인
     */
    @GetMapping("/can-manage/{targetUserId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> canManageUser(
            @PathVariable String targetUserId,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            boolean canManage = userService.canManageUser(adminUserId, targetUserId);

            Map<String, Object> response = new HashMap<>();
            response.put("canManage", canManage);
            response.put("targetUserId", targetUserId);
            response.put("adminUserId", adminUserId);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서 목록 조회 (관리 가능한 부서들)
     */
    @GetMapping("/departments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getManageableDepartments(Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            List<UserEntity> manageableUsers = userService.getManageableUsers(adminUserId);

            // 부서 코드 중복 제거
            List<String> departments = manageableUsers.stream()
                    .map(UserEntity::getDeptCode)
                    .distinct()
                    .sorted()
                    .toList();

            return ResponseEntity.ok(Map.of("departments", departments));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * JobLevel별 사용자 통계
     */
    @GetMapping("/statistics/job-level")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getJobLevelStatistics(Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            List<UserEntity> manageableUsers = userService.getManageableUsers(adminUserId);

            Map<String, Long> statistics = manageableUsers.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            UserEntity::getJobLevel,
                            java.util.stream.Collectors.counting()
                    ));

            return ResponseEntity.ok(Map.of("jobLevelStatistics", statistics));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Role별 사용자 통계
     */
    @GetMapping("/statistics/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getRoleStatistics(Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            List<UserEntity> manageableUsers = userService.getManageableUsers(adminUserId);

            Map<String, Long> statistics = manageableUsers.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            user -> user.getRole().toString(),
                            java.util.stream.Collectors.counting()
                    ));

            return ResponseEntity.ok(Map.of("roleStatistics", statistics));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/my-department-users")
    @PreAuthorize("hasAuthority('ADMIN')") // 변경: hasRole → hasAuthority (DB role 매칭)
    public ResponseEntity<?> getMyDepartmentUsers(Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            UserEntity admin = userRepository.findByUserId(adminUserId)
                    .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

            // ✅ ADMIN Role 확인 제거 (PreAuthorize로 대체, 중복 방지)
            // if (!admin.isAdmin()) { ... } 주석 처리

            int adminLevel;
            try {
                adminLevel = Integer.parseInt(admin.getJobLevel());
            } catch (NumberFormatException e) {
                log.error("JobLevel 파싱 오류: userId={}, jobLevel={}", adminUserId, admin.getJobLevel());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "JobLevel 파싱 오류"));
            }

            Set<PermissionType> adminPermissions = permissionService.getAllUserPermissions(adminUserId);
            List<UserEntity> users;

            // ✅ jobLevel에 따른 분기 처리 (기존 유지)
            if (adminLevel == 1) {
                // jobLevel 1: 자신의 부서만
                users = userService.getUsersByDeptForAdmin(adminUserId, admin.getDeptCode());
                log.info("부서장 {} - 부서 {} 사용자 조회: {}명", adminUserId, admin.getDeptCode(), users.size());
            }
            else if (adminLevel >= 2) {
                // jobLevel 2 이상: 모든 사용자
                users = userService.getManageableUsers(adminUserId);
                log.info("관리자 {} (level {}) - 전체 사용자 조회: {}명", adminUserId, adminLevel, users.size());
            }
            else if (adminLevel == 0 && adminPermissions.contains(PermissionType.MANAGE_USERS)) {
                // jobLevel 0 + MANAGE_USERS 권한
                users = userService.getManageableUsers(adminUserId);
                log.info("권한 보유 사용자 {} - 전체 사용자 조회: {}명", adminUserId, users.size());
            }
            else {
                // 권한 없음
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "사용자 조회 권한이 없습니다."));
            }

            // 추가: DTO 변환으로 직렬화 에러 방지 (UserResponseDto 사용, deptCode base 그룹화)
            List<UserResponseDto> dtos = users.stream()
                    .map(u -> {
                        UserResponseDto dto = new UserResponseDto();
                        dto.setUserId(u.getUserId());
                        dto.setUserName(u.getUserName());
                        dto.setDeptCode(u.getDeptCode().replaceAll("\\d+$", "")); // base 코드 (OS1 → OS)
                        dto.setJobLevel(u.getJobLevel());
                        dto.setRole(u.getRole().toString()); // enum to string
                        dto.setUseFlag(u.getUseFlag());
                        // Department 필드 생략 (LAZY 에러 피함)
                        return dto;
                    }).collect(Collectors.toList());

            return ResponseEntity.ok(dtos); // 변경: users → dtos 반환

        } catch (RuntimeException e) { // 추가: 세부 예외 처리
            log.error("런타임 오류: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("사용자 조회 오류: userId={}", authentication.getPrincipal(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "사용자 조회 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }
}