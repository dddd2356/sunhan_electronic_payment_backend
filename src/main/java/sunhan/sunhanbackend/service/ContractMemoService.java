package sunhan.sunhanbackend.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.ContractMemo;
import sunhan.sunhanbackend.enums.PermissionType;
import sunhan.sunhanbackend.repository.mysql.ContractMemoRepository;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContractMemoService {
    private final ContractMemoRepository memoRepository;
    private final PermissionService permissionService;  // 의존성 주입 (생성자에 추가)

    @Transactional
    public ContractMemo createMemo(String targetUserId, String memoText, String createdBy) {
        if (!permissionService.getAllUserPermissions(createdBy).contains(PermissionType.HR_CONTRACT)) {
            throw new AccessDeniedException("메모 작성 권한이 없습니다.");
        }
        ContractMemo memo = new ContractMemo();
        memo.setTargetUserId(targetUserId);
        memo.setMemoText(memoText);
        memo.setCreatedBy(createdBy);
        return memoRepository.save(memo);
    }

    @Transactional
    public ContractMemo updateMemo(Long memoId, String memoText, String updatedBy) {
        ContractMemo memo = memoRepository.findById(memoId).orElseThrow(() -> new EntityNotFoundException("메모를 찾을 수 없습니다."));
        if (!permissionService.getAllUserPermissions(updatedBy).contains(PermissionType.HR_CONTRACT)) {
            throw new AccessDeniedException("메모 수정 권한이 없습니다.");
        }
        memo.setMemoText(memoText);
        return memoRepository.save(memo);
    }

    @Transactional
    public void deleteMemo(Long memoId, String deletedBy) {
        ContractMemo memo = memoRepository.findById(memoId).orElseThrow(() -> new EntityNotFoundException("메모를 찾을 수 없습니다."));
        if (!permissionService.getAllUserPermissions(deletedBy).contains(PermissionType.HR_CONTRACT)) {
            throw new AccessDeniedException("메모 삭제 권한이 없습니다.");
        }
        memoRepository.delete(memo);
    }

    public List<ContractMemo> getMemosForUser(String targetUserId, String requesterId) {
        Set<PermissionType> perms = permissionService.getAllUserPermissions(requesterId);
        if (!targetUserId.equals(requesterId) && !perms.contains(PermissionType.HR_CONTRACT)) {
            throw new AccessDeniedException("메모 조회 권한이 없습니다.");
        }
        return memoRepository.findByTargetUserId(targetUserId);
    }
}