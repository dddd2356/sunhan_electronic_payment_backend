package sunhan.sunhanbackend.controller.position;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.position.Position;
import sunhan.sunhanbackend.service.position.PositionService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/positions")
@RequiredArgsConstructor
@Slf4j
public class PositionController {

    private final PositionService positionService;

    /**
     * 직책 생성
     * POST /api/v1/positions
     */
    @PostMapping
    public ResponseEntity<?> createPosition(
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            String deptCode = (String) request.get("deptCode");
            String positionName = (String) request.get("positionName");
            Integer displayOrder = request.get("displayOrder") != null ?
                    Integer.valueOf(request.get("displayOrder").toString()) : null;

            Position position = positionService.createPosition(
                    deptCode, positionName, displayOrder, userId);

            return ResponseEntity.status(HttpStatus.CREATED).body(position);

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("직책 생성 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "직책 생성 중 오류가 발생했습니다."));
        }
    }

    /**
     * 부서별 직책 목록 조회
     * GET /api/v1/positions/department/{deptCode}
     */
    @GetMapping("/department/{deptCode}")
    public ResponseEntity<?> getPositionsByDept(@PathVariable String deptCode) {
        try {
            List<Position> positions = positionService.getPositionsByDept(deptCode);
            return ResponseEntity.ok(positions);
        } catch (Exception e) {
            log.error("직책 목록 조회 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "직책 목록 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 직책 수정
     * PUT /api/v1/positions/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updatePosition(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            String positionName = (String) request.get("positionName");
            Integer displayOrder = request.get("displayOrder") != null ?
                    Integer.valueOf(request.get("displayOrder").toString()) : null;

            Position position = positionService.updatePosition(
                    id, positionName, displayOrder, userId);

            return ResponseEntity.ok(position);

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("직책 수정 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "직책 수정 중 오류가 발생했습니다."));
        }
    }

    /**
     * 직책 삭제 (비활성화)
     * DELETE /api/v1/positions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePosition(
            @PathVariable Long id,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();
            positionService.deletePosition(id, userId);

            return ResponseEntity.ok(Map.of("message", "직책이 삭제되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("직책 삭제 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "직책 삭제 중 오류가 발생했습니다."));
        }
    }

    /**
     * 직책 순서 변경
     * PUT /api/v1/positions/department/{deptCode}/reorder
     */
    @PutMapping("/department/{deptCode}/reorder")
    public ResponseEntity<?> reorderPositions(
            @PathVariable String deptCode,
            @RequestBody Map<String, Object> request,
            Authentication auth
    ) {
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            String userId = auth.getName();

            @SuppressWarnings("unchecked")
            List<Integer> positionIdsRaw = (List<Integer>) request.get("positionIds");
            List<Long> positionIds = positionIdsRaw.stream()
                    .map(Integer::longValue)
                    .toList();

            positionService.reorderPositions(deptCode, positionIds, userId);

            return ResponseEntity.ok(Map.of("message", "순서가 변경되었습니다."));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("직책 순서 변경 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "순서 변경 중 오류가 발생했습니다."));
        }
    }
}