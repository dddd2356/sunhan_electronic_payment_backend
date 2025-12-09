package sunhan.sunhanbackend.repository.mysql.workschedule;

import org.springframework.data.jpa.repository.JpaRepository;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleTemplate;

import java.util.List;
import java.util.Optional;

public interface WorkScheduleTemplateRepository extends JpaRepository<WorkScheduleTemplate, Long> {
    List<WorkScheduleTemplate> findByCreatedByOrderByUpdatedAtDesc(String createdBy);

    Optional<WorkScheduleTemplate> findByIdAndCreatedBy(Long id, String createdBy);
}