package sunhan.sunhanbackend.repository.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sunhan.sunhanbackend.entity.mysql.UserAnnualVacationHistory;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAnnualVacationHistoryRepository extends JpaRepository<UserAnnualVacationHistory, Long> {

    Optional<UserAnnualVacationHistory> findByUserIdAndYear(String userId, Integer year);

    List<UserAnnualVacationHistory> findByUserIdOrderByYearDesc(String userId);

    List<UserAnnualVacationHistory> findByYear(Integer year);

    @Query("SELECT h FROM UserAnnualVacationHistory h WHERE h.year BETWEEN :startYear AND :endYear ORDER BY h.year")
    List<UserAnnualVacationHistory> findByYearBetween(@Param("startYear") Integer startYear,
                                                      @Param("endYear") Integer endYear);

    @Query("SELECT h FROM UserAnnualVacationHistory h WHERE h.userId = :userId AND h.year BETWEEN :startYear AND :endYear ORDER BY h.year")
    List<UserAnnualVacationHistory> findByUserIdAndYearBetween(@Param("userId") String userId,
                                                               @Param("startYear") Integer startYear,
                                                               @Param("endYear") Integer endYear);

    @Query("SELECT h FROM UserAnnualVacationHistory h WHERE h.userId IN :userIds AND h.year = :year")
    List<UserAnnualVacationHistory> findByUserIdsAndYear(@Param("userIds") List<String> userIds,
                                                         @Param("year") Integer year);

}