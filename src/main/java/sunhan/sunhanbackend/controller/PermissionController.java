package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.DeptPermissionEntity;
import sunhan.sunhanbackend.entity.mysql.UserPermissionEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.service.PermissionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 사용자의 권한 확인
     */
    @GetMapping("/check/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> checkUserPermissions(@PathVariable String userId) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("userId", userId);
            Set<PermissionType> userPermissions = permissionService.getAllUserPermissions(userId);
            response.put("hasHrLeavePermission", userPermissions.contains(PermissionType.HR_LEAVE_APPLICATION));
            response.put("hasHrContractPermission", userPermissions.contains(PermissionType.HR_CONTRACT));
            response.put("hasAnyHrPermission",
                    userPermissions.contains(PermissionType.HR_LEAVE_APPLICATION) ||
                            userPermissions.contains(PermissionType.HR_CONTRACT));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서에 인사 권한 부여
     */
    @PostMapping("/department/grant")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> grantDeptPermission(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            String deptCode = request.get("deptCode");
            String permissionTypeStr = request.get("permissionType");

            PermissionType permissionType = PermissionType.valueOf(permissionTypeStr);

            permissionService.grantDeptPermission(adminUserId, deptCode, permissionType);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "부서 권한 부여 완료");
            response.put("deptCode", deptCode);
            response.put("permissionType", permissionType);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + request.get("permissionType")));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서에서 인사 권한 제거
     */
    @PostMapping("/department/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> revokeDeptPermission(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            String deptCode = request.get("deptCode");
            String permissionTypeStr = request.get("permissionType");

            PermissionType permissionType = PermissionType.valueOf(permissionTypeStr);

            permissionService.revokeDeptPermission(adminUserId, deptCode, permissionType);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "부서 권한 제거 완료");
            response.put("deptCode", deptCode);
            response.put("permissionType", permissionType);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + request.get("permissionType")));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 개인에게 인사 권한 부여
     */
    @PostMapping("/user/grant")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> grantUserPermission(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            String targetUserId = request.get("targetUserId");
            String permissionTypeStr = request.get("permissionType");

            PermissionType permissionType = PermissionType.valueOf(permissionTypeStr);

            permissionService.grantUserPermission(adminUserId, targetUserId, permissionType);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "개인 권한 부여 완료");
            response.put("targetUserId", targetUserId);
            response.put("permissionType", permissionType);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + request.get("permissionType")));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 개인에서 인사 권한 제거
     */
    @PostMapping("/user/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> revokeUserPermission(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        try {
            String adminUserId = (String) authentication.getPrincipal();
            String targetUserId = request.get("targetUserId");
            String permissionTypeStr = request.get("permissionType");

            PermissionType permissionType = PermissionType.valueOf(permissionTypeStr);

            permissionService.revokeUserPermission(adminUserId, targetUserId, permissionType);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "개인 권한 제거 완료");
            response.put("targetUserId", targetUserId);
            response.put("permissionType", permissionType);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + request.get("permissionType")));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서의 모든 권한 조회
     */
    @GetMapping("/department/{deptCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getDeptPermissions(@PathVariable String deptCode) {
        try {
            List<DeptPermissionEntity> permissions = permissionService.getDeptPermissions(deptCode);

            Map<String, Object> response = new HashMap<>();
            response.put("deptCode", deptCode);
            response.put("permissions", permissions);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 사용자의 개인 권한 조회
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getUserPermissions(@PathVariable String userId) {
        try {
            List<UserPermissionEntity> permissions = permissionService.getUserPermissions(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("userId", userId);
            response.put("permissions", permissions);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 특정 권한을 가진 모든 부서 조회
     */
    @GetMapping("/departments/{permissionType}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getDepartmentsWithPermission(@PathVariable String permissionType) {
        try {
            PermissionType permission = PermissionType.valueOf(permissionType);
            List<String> deptCodes = permissionService.getDeptCodesWithPermission(permission);

            Map<String, Object> response = new HashMap<>();
            response.put("permissionType", permission);
            response.put("deptCodes", deptCodes);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + permissionType));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 특정 권한을 가진 모든 사용자 조회
     */
    @GetMapping("/users/{permissionType}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getUsersWithPermission(@PathVariable String permissionType) {
        try {
            PermissionType permission = PermissionType.valueOf(permissionType);
            List<String> userIds = permissionService.getUserIdsWithPermission(permission);

            Map<String, Object> response = new HashMap<>();
            response.put("permissionType", permission);
            response.put("userIds", userIds);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "올바르지 않은 권한 타입입니다: " + permissionType));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 모든 권한 타입 조회 (프론트엔드에서 사용)
     */
    @GetMapping("/types")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getPermissionTypes() {
        Map<String, Object> response = new HashMap<>();
        response.put("permissionTypes", PermissionType.values());
        return ResponseEntity.ok(response);
    }
}