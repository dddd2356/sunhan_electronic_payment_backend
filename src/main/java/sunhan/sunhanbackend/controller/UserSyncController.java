package sunhan.sunhanbackend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sunhan.sunhanbackend.dto.response.SyncResult;
import sunhan.sunhanbackend.service.UserSyncService;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/admin/sync")
@Slf4j
public class UserSyncController {

    @Autowired
    private UserSyncService userSyncService;

    /**
     * 전체 사용자 정보 동기화
     * (useFlag, userName, deptCode, jobType, startDate)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/all")
    public ResponseEntity<SyncResult> syncAllUsers() {
        try {
            log.info("관리자가 전체 사용자 동기화를 요청했습니다.");
            SyncResult result = userSyncService.syncAllUsers();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("전체 사용자 동기화 실행 중 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(0, 0, 1, Arrays.asList(e.getMessage())));
        }
    }

    /**
     * 변경된 사용자 정보만 동기화 (효율적)
     * (useFlag, userName, deptCode, jobType, startDate)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/changed")
    public ResponseEntity<SyncResult> syncChangedUsers() {
        try {
            log.info("관리자가 변경된 사용자 동기화를 요청했습니다.");
            SyncResult result = userSyncService.syncChangedUsers();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("변경된 사용자 동기화 실행 중 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(0, 0, 1, Arrays.asList(e.getMessage())));
        }
    }

    /**
     * 특정 사용자 정보 동기화
     * (useFlag, userName, deptCode, jobType, startDate)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}")
    public ResponseEntity<SyncResult> syncSingleUser(@PathVariable String userId) {
        try {
            log.info("관리자가 사용자 {}의 동기화를 요청했습니다.", userId);
            boolean updated = userSyncService.syncSingleUser(userId);

            int totalCount = 1;
            int successCount = updated ? 1 : 0;
            int errorCount = 0;

            String message = updated
                    ? "사용자 정보 동기화 완료: " + userId
                    : "변경사항 없음: " + userId;

            return ResponseEntity.ok(new SyncResult(totalCount, successCount, errorCount, List.of(message)));
        } catch (Exception e) {
            log.error("개별 사용자 동기화 실행 중 오류: {}", userId, e);
            List<String> errors = List.of("동기화 실패: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(1, 0, 1, errors));
        }
    }
}