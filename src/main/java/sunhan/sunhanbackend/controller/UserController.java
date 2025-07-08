package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sunhan.sunhanbackend.entity.UserEntity;
import sunhan.sunhanbackend.service.UserService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // principal이 String(userId)로 들어오는 경우
        String userId = (String) authentication.getPrincipal();
        UserEntity user = userService.getUserInfo(userId);  // DB에서 로드

        Map<String, Object> response = new HashMap<>();
        response.put("userId", user.getUserId());
        response.put("userName", user.getUserName());
        response.put("jobLevel", user.getJobLevel());
        response.put("deptCode", user.getDeptCode());
        response.put("isAdmin", user.isAdmin());
        return ResponseEntity.ok(response);
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
    public ResponseEntity<UserEntity> getUserInfo(@PathVariable String userId) {
        try {
            UserEntity user = userService.getUserInfo(userId);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}