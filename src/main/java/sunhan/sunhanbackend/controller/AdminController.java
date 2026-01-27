package sunhan.sunhanbackend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.dto.request.ResetPasswordRequest;
import sunhan.sunhanbackend.dto.request.UpdateUserFlagRequestDto;
import sunhan.sunhanbackend.dto.request.auth.UserRegistrationDto;
import sunhan.sunhanbackend.dto.request.permissions.GrantRoleByConditionDto;
import sunhan.sunhanbackend.dto.request.permissions.GrantRoleByUserIdDto;
import sunhan.sunhanbackend.dto.request.permissions.UpdateJobLevelRequestDto;
import sunhan.sunhanbackend.dto.response.AdminStatsDto;
import sunhan.sunhanbackend.dto.response.UserListResponseDto;
import sunhan.sunhanbackend.dto.response.UserResponseDto;
import sunhan.sunhanbackend.dto.response.VacationStatusResponseDto;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;
import sunhan.sunhanbackend.enums.LeaveType;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.UserRepository;
import sunhan.sunhanbackend.service.LeaveApplicationService;
import sunhan.sunhanbackend.service.PermissionService;
import sunhan.sunhanbackend.service.UserService;
import sunhan.sunhanbackend.service.VacationService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController  // Controller 클래스에 @RestController 추가
@RequestMapping("/api/v1/admin")
@Slf4j
public class AdminController {
    private final UserService userService;  // UserService 주입
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;  // JwtProvider 변수 선언
    private final PermissionService permissionService;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final VacationService vacationService;
    private final PasswordEncoder passwordEncoder;
    @Autowired
    public AdminController(UserService userService, UserRepository userRepository, JwtProvider jwtProvider, PermissionService permissionService, LeaveApplicationRepository leaveApplicationRepository, VacationService vacationService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.userRepository = userRepository;  // 생성자 주입
        this.jwtProvider = jwtProvider;
        this.permissionService = permissionService;
        this.leaveApplicationRepository = leaveApplicationRepository;
        this.vacationService = vacationService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 모든 사용자 조회 (휴가 정보 포함)
     */
    /**
     * 모든 사용자 조회 (휴가 정보 포함)
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponseDto>> getAllUsers(
            @RequestParam(required = false) Integer year
    ) {
        List<UserEntity> users = userService.findAllUsers();

        // ✅ 연도 기본값 설정
        final Integer targetYear = (year != null) ? year : LocalDate.now().getYear();

        List<UserResponseDto> dtos = users.stream().map(u -> {
            UserResponseDto dto = new UserResponseDto();
            dto.setUserId(u.getUserId());
            dto.setUserName(u.getUserName());
            dto.setDeptCode(u.getDeptCode());
            dto.setJobLevel(u.getJobLevel());
            dto.setRole(u.getRole() == null ? null : u.getRole().toString());
            dto.setUseFlag(u.getUseFlag());

            // ✅ VacationService 사용
            try {
                VacationStatusResponseDto vacationStatus = vacationService.getVacationStatus(
                        u.getUserId(),
                        targetYear
                );
                dto.setTotalVacationDays(vacationStatus.getAnnualTotalDays());
                dto.setUsedVacationDays(vacationStatus.getAnnualUsedDays());
                dto.setRemainingVacationDays(vacationStatus.getAnnualRemainingDays());
            } catch (Exception e) {
                log.warn("연차 정보 조회 실패: userId={}, year={}", u.getUserId(), targetYear, e);
                dto.setTotalVacationDays(0.0);
                dto.setUsedVacationDays(0.0);
                dto.setRemainingVacationDays(0.0);
            }

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

//    @GetMapping("/my-department-users")
//    @PreAuthorize("hasAuthority('ADMIN')") // 변경: hasRole → hasAuthority (DB role 매칭)
//    public ResponseEntity<?> getMyDepartmentUsers(Authentication authentication) {
//        try {
//            String adminUserId = (String) authentication.getPrincipal();
//            UserEntity admin = userRepository.findByUserId(adminUserId)
//                    .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
//
//            // ✅ ADMIN Role 확인 제거 (PreAuthorize로 대체, 중복 방지)
//            // if (!admin.isAdmin()) { ... } 주석 처리
//
//            int adminLevel;
//            try {
//                adminLevel = Integer.parseInt(admin.getJobLevel());
//            } catch (NumberFormatException e) {
//                log.error("JobLevel 파싱 오류: userId={}, jobLevel={}", adminUserId, admin.getJobLevel());
//                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                        .body(Map.of("error", "JobLevel 파싱 오류"));
//            }
//
//            Set<PermissionType> adminPermissions = permissionService.getAllUserPermissions(adminUserId);
//            List<UserEntity> users;
//
//            // ✅ jobLevel에 따른 분기 처리 (기존 유지)
//            if (adminLevel == 1) {
//                // jobLevel 1: 자신의 부서만
//                users = userService.getUsersByDeptForAdmin(adminUserId, admin.getDeptCode());
//                log.info("부서장 {} - 부서 {} 사용자 조회: {}명", adminUserId, admin.getDeptCode(), users.size());
//            }
//            else if (adminLevel >= 2) {
//                // jobLevel 2 이상: 모든 사용자
//                users = userService.getManageableUsers(adminUserId);
//                log.info("관리자 {} (level {}) - 전체 사용자 조회: {}명", adminUserId, adminLevel, users.size());
//            }
//            else if (adminLevel == 0 && adminPermissions.contains(PermissionType.MANAGE_USERS)) {
//                // jobLevel 0 + MANAGE_USERS 권한
//                users = userService.getManageableUsers(adminUserId);
//                log.info("권한 보유 사용자 {} - 전체 사용자 조회: {}명", adminUserId, users.size());
//            }
//            else {
//                // 권한 없음
//                return ResponseEntity.status(HttpStatus.FORBIDDEN)
//                        .body(Map.of("error", "사용자 조회 권한이 없습니다."));
//            }
//
//            // 추가: DTO 변환으로 직렬화 에러 방지 (UserResponseDto 사용, deptCode base 그룹화)
//            List<UserResponseDto> dtos = users.stream()
//                    .map(u -> {
//                        UserResponseDto dto = new UserResponseDto();
//                        dto.setUserId(u.getUserId());
//                        dto.setUserName(u.getUserName());
//                        dto.setDeptCode(u.getDeptCode().replaceAll("\\d+$", "")); // base 코드 (OS1 → OS)
//                        dto.setJobLevel(u.getJobLevel());
//                        dto.setRole(u.getRole().toString()); // enum to string
//                        dto.setUseFlag(u.getUseFlag());
//                        // Department 필드 생략 (LAZY 에러 피함)
//                        return dto;
//                    }).collect(Collectors.toList());
//
//            return ResponseEntity.ok(dtos); // 변경: users → dtos 반환
//
//        } catch (RuntimeException e) { // 추가: 세부 예외 처리
//            log.error("런타임 오류: {}", e.getMessage());
//            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
//                    .body(Map.of("error", e.getMessage()));
//        } catch (Exception e) {
//            log.error("사용자 조회 오류: userId={}", authentication.getPrincipal(), e);
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Map.of("error", "사용자 조회 중 오류가 발생했습니다: " + e.getMessage()));
//        }
//    }

    /**
     * 💡 [PAGING] 사용자 목록 조회 (Admin 전용) - 페이지네이션 적용
     * GET /api/v1/admin/my-department-users?page=0&size=15&showAll=false&searchTerm=
     */
    @GetMapping("/my-department-users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getAllUsersByAdmin(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page, // 💡 페이지 번호
            @RequestParam(defaultValue = "10") int size, // 💡 페이지 크기
            @RequestParam(defaultValue = "false") boolean showAll, // 💡 활성/비활성 토글
            @RequestParam(required = false) String searchTerm // 💡 검색어
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            // ✅ 추가: 관리자 정보 조회
            UserEntity admin = userRepository.findByUserId(adminUserId)
                    .orElseThrow(() -> new RuntimeException("관리자를 찾을 수 없습니다."));

            int adminLevel = Integer.parseInt(admin.getJobLevel());

            // ✅ 추가: jobLevel에 따른 사용자 목록 필터링
            Pageable pageable = PageRequest.of(page, size);
            UserListResponseDto pagedUsers;

            if (adminLevel == 1) {
                // jobLevel 1: 본인 부서만 조회
                String deptBase = admin.getDeptCode().replaceAll("\\d+$", "");
                pagedUsers = userService.getDepartmentUsersByAdminWithPaging(
                        deptBase, showAll, searchTerm, pageable);
                log.info("부서장 {} - 부서 {} 사용자 조회", adminUserId, deptBase);
            } else if (adminLevel >= 2) {
                // jobLevel 2 이상: 전체 조회
                pagedUsers = userService.getAllUsersByAdminWithPaging(showAll, searchTerm, pageable);
                log.info("관리자 {} (level {}) - 전체 사용자 조회", adminUserId, adminLevel);
            } else {
                // jobLevel 0: 권한에 따라 결정
                Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
                if (permissions.contains(PermissionType.MANAGE_USERS)) {
                    pagedUsers = userService.getAllUsersByAdminWithPaging(showAll, searchTerm, pageable);
                } else {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("error", "사용자 조회 권한이 없습니다."));
                }
            }

            return ResponseEntity.ok(pagedUsers);

        } catch (Exception e) {
            log.error("사용자 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "사용자 조회 오류가 발생했습니다."));
        }
    }

    /**
     * 💡 [NEW] 권한 통합 조회 API - N+1 문제 해결
     * GET /api/v1/admin/permissions/users/all
     * 모든 사용자의 개별 권한을 한 번에 가져옵니다.
     */
    @GetMapping("/permissions/users/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getAllUserPermissions() {
        try {
            // PermissionService에 통합 조회 메서드 추가가 필요함
            Map<String, Set<PermissionType>> userPermissions = permissionService.getAllUserPermissionsGroupedByUserId();

            // 응답 DTO 생성
            Map<String, List<String>> responseMap = userPermissions.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream().map(PermissionType::name).collect(Collectors.toList())
                    ));

            // DTO로 감싸서 반환
            return ResponseEntity.ok(Map.of("userPermissions", responseMap));

        } catch (Exception e) {
            log.error("전체 사용자 권한 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "권한 정보를 가져오는 중 오류가 발생했습니다."));
        }
    }

    /**
     * 💡 [NEW] 부서 권한 통합 조회 API - N+1 문제 해결
     * GET /api/v1/admin/permissions/departments/all
     */
    @GetMapping("/permissions/departments/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getAllDeptPermissions() {
        try {
            // PermissionService에 통합 조회 메서드 추가가 필요함
            Map<String, Set<PermissionType>> deptPermissions = permissionService.getAllDeptPermissionsGroupedByDeptCode();

            Map<String, List<String>> responseMap = deptPermissions.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream().map(PermissionType::name).collect(Collectors.toList())
                    ));

            return ResponseEntity.ok(Map.of("deptPermissions", responseMap));
        } catch (Exception e) {
            log.error("전체 부서 권한 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "부서 권한 정보를 가져오는 중 오류가 발생했습니다."));
        }
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminStatsDto> getAdminDashboardStats() {
        try {
            AdminStatsDto stats = userService.getAdminDashboardStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("통계 데이터 조회 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/reset-user-password")
    @PreAuthorize("hasRole('ADMIN')") // ✅ 추가 (보안 강화)
    public ResponseEntity<?> resetUserPassword(
            @RequestBody ResetPasswordRequest request,
            Authentication authentication
    ) {
        try {
            // ✅ Authentication에서 userId 추출
            String adminUserId = (String) authentication.getPrincipal();

            UserEntity admin = userRepository.findByUserId(adminUserId)
                    .orElseThrow(() -> new IllegalArgumentException("관리자를 찾을 수 없습니다."));

            int adminLevel = Integer.parseInt(admin.getJobLevel());
            if (adminLevel != 6) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "최고관리자(jobLevel 6)만 비밀번호를 변경할 수 있습니다."));
            }

            UserEntity targetUser = userRepository.findByUserId(request.getTargetUserId())
                    .orElseThrow(() -> new IllegalArgumentException("대상 사용자를 찾을 수 없습니다."));

            // 비밀번호 변경
            targetUser.setPasswd(passwordEncoder.encode(request.getNewPassword()));
            targetUser.setPasswordChangeRequired(true);
            userRepository.save(targetUser);

            log.info("최고관리자 {}가 사용자 {}의 비밀번호를 변경함",
                    admin.getUserId(), targetUser.getUserId());

            return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "잘못된 jobLevel 형식입니다."));
        } catch (Exception e) {
            log.error("비밀번호 변경 실패", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 신규 회원 등록 (MANAGE_USERS 권한 필요)
     */
    @PostMapping("/users/register")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> registerUser(
            @RequestBody UserRegistrationDto dto,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            // 권한 체크
            Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
            if (!permissions.contains(PermissionType.MANAGE_USERS)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "회원 등록 권한이 없습니다."));
            }

            UserEntity newUser = userService.registerUser(dto);

            return ResponseEntity.ok(Map.of(
                    "message", "회원 등록이 완료되었습니다.",
                    "userId", newUser.getUserId(),
                    "userName", newUser.getUserName()
            ));

        } catch (Exception e) {
            log.error("회원 등록 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 사용자 부서 변경 (인사이동)
     */
    @PutMapping("/users/{userId}/department")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeUserDepartment(
            @PathVariable String userId,
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            String newDeptCode = request.get("deptCode");

            if (newDeptCode == null || newDeptCode.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "부서 코드를 입력해주세요."));
            }

            UserEntity updatedUser = userService.changeUserDepartment(adminUserId, userId, newDeptCode);

            return ResponseEntity.ok(Map.of(
                    "message", "부서가 변경되었습니다.",
                    "userId", updatedUser.getUserId(),
                    "newDeptCode", updatedUser.getDeptCode()
            ));

        } catch (Exception e) {
            log.error("부서 변경 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 사용자 활성/비활성 상태 변경
     */
    @PutMapping("/users/{userId}/toggle-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> toggleUserStatus(
            @PathVariable String userId,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            UserEntity updatedUser = userService.toggleUserStatus(adminUserId, userId);

            return ResponseEntity.ok(Map.of(
                    "message", "사용자 상태가 변경되었습니다.",
                    "userId", updatedUser.getUserId(),
                    "useFlag", updatedUser.getUseFlag()
            ));

        } catch (Exception e) {
            log.error("사용자 상태 변경 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}