package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.LeaveApplicationDay;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeaveApplicationDayRepository extends JpaRepository<LeaveApplicationDay, Long> {
    List<LeaveApplicationDay> findByLeaveApplicationId(Long leaveApplicationId);

    List<LeaveApplicationDay> findByDateBetween(LocalDate startDate, LocalDate endDate);
}
