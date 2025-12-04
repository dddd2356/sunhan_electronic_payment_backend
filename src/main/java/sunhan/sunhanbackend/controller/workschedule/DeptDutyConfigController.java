package sunhan.sunhanbackend.controller.workschedule;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.workschedule.DeptDutyConfig;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.repository.mysql.workschedule.DeptDutyConfigRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dept-duty-config")
@RequiredArgsConstructor
@Slf4j
public class DeptDutyConfigController {

    private final DeptDutyConfigRepository configRepository;
    private final WorkScheduleRepository scheduleRepository;

    // ✅ scheduleId로 조회
    @GetMapping("/schedule/{scheduleId}")
    public ResponseEntity<?> getConfig(@PathVariable Long scheduleId) {
        try {
            DeptDutyConfig config = configRepository.findByScheduleId(scheduleId)
                    .orElseGet(() -> createDefaultConfig(scheduleId));
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("당직 설정 조회 실패: scheduleId={}", scheduleId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "설정 조회 실패"));
        }
    }

    @PostMapping
    public ResponseEntity<?> saveConfig(
            @RequestBody DeptDutyConfig config,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            // ✅ scheduleId 검증
            if (config.getScheduleId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "scheduleId가 필요합니다."));
            }

            // ✅ 근무표 조회 및 권한 검증
            WorkSchedule schedule = scheduleRepository.findById(config.getScheduleId())
                    .orElseThrow(() -> new EntityNotFoundException("근무표를 찾을 수 없습니다."));

            // 작성자만 수정 가능
            if (!schedule.getCreatedBy().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "작성자만 설정을 변경할 수 있습니다."));
            }

            // DRAFT 상태에서만 수정 가능
            if (schedule.getApprovalStatus() != WorkSchedule.ScheduleStatus.DRAFT) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "임시저장 상태에서만 설정을 변경할 수 있습니다."));
            }

            // ✅ 기존 설정이 있으면 업데이트
            DeptDutyConfig existing = configRepository.findByScheduleId(config.getScheduleId())
                    .orElse(null);

            if (existing != null) {
                config.setId(existing.getId());
                config.setCreatedAt(existing.getCreatedAt());
            }

            DeptDutyConfig saved = configRepository.save(config);
            log.info("당직 설정 저장 완료: scheduleId={}, mode={}",
                    config.getScheduleId(), config.getDutyMode());
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            log.error("당직 설정 저장 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private DeptDutyConfig createDefaultConfig(Long scheduleId) {
        DeptDutyConfig config = new DeptDutyConfig();
        config.setScheduleId(scheduleId);
        config.setDutyMode(DeptDutyConfig.DutyMode.NIGHT_SHIFT);
        config.setDisplayName("나이트");
        config.setCellSymbol("N");
        return config;
    }
}