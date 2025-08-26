package sunhan.sunhanbackend.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import sunhan.sunhanbackend.enums.LeaveApplicationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class LeaveSummaryDto {
    private Long id;
    private LocalDate startDate;
    private LocalDate endDate;
    private Double totalDays;
    private LeaveApplicationStatus status;
    private String applicantName;
    private String substituteName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public LeaveSummaryDto(Long id,
                           LocalDate startDate,
                           LocalDate endDate,
                           Double totalDays,
                           LeaveApplicationStatus status,
                           String applicantName,
                           String substituteName,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
        this.id = id;
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalDays = totalDays;
        this.status = status;
        this.applicantName = applicantName;
        this.substituteName = substituteName;
        // ✅ null 체크 로직 추가
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
        this.updatedAt = (updatedAt != null) ? updatedAt : LocalDateTime.now();
    }
}