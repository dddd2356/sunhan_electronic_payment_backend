package sunhan.sunhanbackend.repository.oracle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.oracle.OracleEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface OracleRepository extends JpaRepository<OracleEntity, String> {
    Optional<OracleEntity> findByUsrId(String usrId);

    // useFlag별 조회 (필요시)
    List<OracleEntity> findByUseFlag(String useFlag);
}