package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.LeaveApplicationAttachment;

@Repository
public interface LeaveApplicationAttachmentRepository extends JpaRepository<LeaveApplicationAttachment, Long> {
}
