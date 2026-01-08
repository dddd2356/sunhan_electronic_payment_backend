package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import sunhan.sunhanbackend.entity.mysql.ContractMemo;

import java.util.List;
import java.util.Optional;

public interface ContractMemoRepository extends JpaRepository<ContractMemo, Long> {
    List<ContractMemo> findByTargetUserId(String targetUserId);
    Optional<ContractMemo> findByTargetUserIdAndCreatedBy(String targetUserId, String createdBy);
}