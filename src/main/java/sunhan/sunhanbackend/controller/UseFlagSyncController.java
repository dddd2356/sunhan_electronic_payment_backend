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
import sunhan.sunhanbackend.service.UseFlagSyncService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/admin/sync")
@Slf4j
public class UseFlagSyncController {

    @Autowired
    private UseFlagSyncService useFlagSyncService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/useflag/all")
    public ResponseEntity<SyncResult> syncAllUseFlags() {
        try {
            SyncResult result = useFlagSyncService.syncAllUseFlags();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("전체 useFlag 동기화 실행 중 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(0, 0, 1, Arrays.asList(e.getMessage())));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/useflag/changed")
    public ResponseEntity<SyncResult> syncChangedUseFlags() {
        try {
            SyncResult result = useFlagSyncService.syncChangedUseFlags();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("변경된 useFlag 동기화 실행 중 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(0, 0, 1, Arrays.asList(e.getMessage())));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/useflag/{userId}")
    public ResponseEntity<SyncResult> syncSingleUserUseFlag(@PathVariable String userId) {
        try {
            boolean updated = useFlagSyncService.syncSingleUserUseFlag(userId);

            int totalCount = 1;
            int successCount = updated ? 1 : 0;
            int errorCount = 0;
            List<String> errors = new ArrayList<>();

            String message = updated ? "useFlag 동기화 완료: " + userId : "변경사항 없음: " + userId;

            return ResponseEntity.ok(new SyncResult(totalCount, successCount, errorCount, errors));
        } catch (Exception e) {
            log.error("개별 사용자 useFlag 동기화 실행 중 오류: {}", userId, e);
            List<String> errors = List.of("동기화 실패: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SyncResult(1, 0, 1, errors));
        }
    }
}