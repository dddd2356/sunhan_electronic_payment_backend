package sunhan.sunhanbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import sunhan.sunhanbackend.entity.mysql.ContractMemo;
import sunhan.sunhanbackend.service.ContractMemoService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memo")
@RequiredArgsConstructor
public class ContractMemoController {
    private final ContractMemoService service;

    @PostMapping("/{userId}")
    public ResponseEntity<ContractMemo> createMemo(@PathVariable String userId, @RequestBody Map<String, String> request, Authentication auth) {
        String creatorId = auth.getName();
        ContractMemo memo = service.createMemo(userId, request.get("memoText"), creatorId);
        return ResponseEntity.ok(memo);
    }

    @PutMapping("/{memoId}")
    public ResponseEntity<ContractMemo> updateMemo(@PathVariable Long memoId, @RequestBody Map<String, String> request, Authentication auth) {
        String updatedBy = auth.getName();
        ContractMemo memo = service.updateMemo(memoId, request.get("memoText"), updatedBy);
        return ResponseEntity.ok(memo);
    }

    @DeleteMapping("/{memoId}")
    public ResponseEntity<Void> deleteMemo(@PathVariable Long memoId, Authentication auth) {
        String deletedBy = auth.getName();
        service.deleteMemo(memoId, deletedBy);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my")
    public ResponseEntity<List<ContractMemo>> getMyMemos(Authentication auth) {
        String userId = auth.getName();
        return ResponseEntity.ok(service.getMemosForUser(userId, userId));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<ContractMemo>> getUserMemos(@PathVariable String userId, Authentication auth) {
        String requesterId = auth.getName();
        return ResponseEntity.ok(service.getMemosForUser(userId, requesterId));
    }
}