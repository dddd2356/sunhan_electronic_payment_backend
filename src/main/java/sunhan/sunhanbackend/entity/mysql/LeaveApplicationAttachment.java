package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_application_attachment")
@Getter
@NoArgsConstructor
public class LeaveApplicationAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_application_id", nullable = false)
    private LeaveApplication leaveApplication;

    @Column(nullable = false)
    private String originalFileName; // 사용자가 업로드한 파일 원본 이름

    @Column(nullable = false)
    private String storedFilePath; // 서버에 저장된 파일 경로 또는 이름

    @Column(nullable = false)
    private String fileType; // 파일 확장자 (e.g., 'image/png', 'application/pdf')

    private long fileSize; // 파일 크기 (bytes)

    @Builder
    public LeaveApplicationAttachment(LeaveApplication leaveApplication, String originalFileName, String storedFilePath, String fileType, long fileSize) {
        this.leaveApplication = leaveApplication;
        this.originalFileName = originalFileName;
        this.storedFilePath = storedFilePath;
        this.fileType = fileType;
        this.fileSize = fileSize;
    }
}