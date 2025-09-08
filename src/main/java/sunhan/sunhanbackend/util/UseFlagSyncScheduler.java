package sunhan.sunhanbackend.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sunhan.sunhanbackend.dto.response.SyncResult;
import sunhan.sunhanbackend.service.UseFlagSyncService;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class UseFlagSyncScheduler {

    @Autowired
    private UseFlagSyncService useFlagSyncService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // 매일 새벽 2시에 자동 동기화
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledUseFlagSync() {
        if (isRunning.compareAndSet(false, true)) {
            try {
                log.info("스케줄된 useFlag 동기화 시작");

                SyncResult result = useFlagSyncService.syncChangedUseFlags();

                log.info("스케줄된 useFlag 동기화 완료 - 변경대상: {}, 업데이트: {}, 실패: {}",
                        result.getTotalCount(), result.getSuccessCount(), result.getErrorCount());

                if (result.getErrorCount() > 0) {
                    log.warn("useFlag 동기화 중 {}건의 오류가 발생했습니다.", result.getErrorCount());
                }

            } catch (Exception e) {
                log.error("스케줄된 useFlag 동기화 중 오류 발생", e);
            } finally {
                isRunning.set(false);
            }
        } else {
            log.warn("이미 useFlag 동기화가 실행 중입니다.");
        }
    }
}