package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.Department;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.DepartmentRepository;
import sunhan.sunhanbackend.service.DepartmentService;
import sunhan.sunhanbackend.service.PermissionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/departments")
@RequiredArgsConstructor
@Slf4j
public class DepartmentController {

    private final DepartmentRepository departmentRepository;
    private final DepartmentService departmentService;
    private final PermissionService permissionService;

    @GetMapping("/names")
    public ResponseEntity<Map<String, String>> getDepartmentNames() {
        Map<String,String> map = departmentRepository.findAllActive()
                .stream()
                .collect(Collectors.toMap(Department::getDeptCode, Department::getDeptName));
        return ResponseEntity.ok(map);
    }

    /**
     * 활성 부서 목록 조회
     */
    @GetMapping
    public ResponseEntity<List<Department>> getActiveDepartments() {
        return ResponseEntity.ok(departmentService.getAllActiveDepartments());
    }

    /**
     * 신규 부서 생성 (MANAGE_USERS 권한 필요)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createDepartment(
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            // 권한 체크
            Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
            if (!permissions.contains(PermissionType.MANAGE_USERS)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "부서 관리 권한이 없습니다."));
            }

            String deptCode = request.get("deptCode");
            String deptName = request.get("deptName");

            Department dept = departmentService.createDepartment(deptCode, deptName);
            return ResponseEntity.ok(dept);
        } catch (Exception e) {
            log.error("부서 생성 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서 삭제 (논리 삭제)
     */
    @DeleteMapping("/{deptCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteDepartment(
            @PathVariable String deptCode,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
            if (!permissions.contains(PermissionType.MANAGE_USERS)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "부서 관리 권한이 없습니다."));
            }

            departmentService.deleteDepartment(deptCode);
            return ResponseEntity.ok(Map.of("message", "부서가 비활성화되었습니다."));
        } catch (Exception e) {
            log.error("부서 삭제 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서명 수정
     */
    @PutMapping("/{deptCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateDepartment(
            @PathVariable String deptCode,
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
            if (!permissions.contains(PermissionType.MANAGE_USERS)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "부서 관리 권한이 없습니다."));
            }

            String newName = request.get("deptName");
            Department dept = departmentService.updateDepartmentName(deptCode, newName);
            return ResponseEntity.ok(dept);
        } catch (Exception e) {
            log.error("부서명 수정 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
    /**
     * 특정 부서의 사용자 목록 조회
     */
    @GetMapping("/{deptCode}/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getDepartmentUsers(@PathVariable String deptCode) {
        try {
            List<UserEntity> users = departmentService.getUsersByDepartment(deptCode);

            List<Map<String, Object>> userList = users.stream()
                    .map(u -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("userId", u.getUserId());
                        map.put("userName", u.getUserName());
                        map.put("jobLevel", u.getJobLevel());
                        map.put("jobType", u.getJobType());
                        map.put("startDate", u.getStartDate());
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(userList);
        } catch (Exception e) {
            log.error("부서 사용자 조회 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 부서 상태 토글 (활성/비활성)
     */
    @PutMapping("/{deptCode}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> toggleDepartmentStatus(
            @PathVariable String deptCode,
            Authentication authentication
    ) {
        try {
            String adminUserId = (String) authentication.getPrincipal();

            Set<PermissionType> permissions = permissionService.getAllUserPermissions(adminUserId);
            if (!permissions.contains(PermissionType.MANAGE_USERS)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "부서 관리 권한이 없습니다."));
            }

            Department dept = departmentService.toggleDepartmentStatus(deptCode);

            String message = "1".equals(dept.getUseFlag())
                    ? "부서가 활성화되었습니다."
                    : "부서가 비활성화되었습니다.";

            return ResponseEntity.ok(Map.of(
                    "message", message,
                    "deptCode", dept.getDeptCode(),
                    "useFlag", dept.getUseFlag()
            ));
        } catch (Exception e) {
            log.error("부서 상태 변경 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 모든 부서 목록 조회 (활성/비활성 포함)
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Department>> getAllDepartments() {
        return ResponseEntity.ok(departmentService.getAllDepartments());
    }
}
