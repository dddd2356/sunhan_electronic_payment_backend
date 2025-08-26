package sunhan.sunhanbackend.entity.mysql;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.ContractStatus;
import sunhan.sunhanbackend.enums.ContractType;

import java.time.LocalDateTime;

@Table(name = "employment_contract")
@Getter
@Setter
@NoArgsConstructor
@Entity
@NamedEntityGraph(
        name = "EmploymentContract.withUsers",
        attributeNodes = {
                @NamedAttributeNode("creator"),
                @NamedAttributeNode("employee")
        }
)
public class EmploymentContract {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false) // ⭐️ 이 부분이 누락되었을 가능성이 높습니다.
    private UserEntity creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false) // 이 부분도 확인해주세요.
    private UserEntity employee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractStatus status;

    @Lob
    @Column(name = "form_data_json", nullable = false)
    private String formDataJson;

    @Column(name = "pdf_url")
    private String pdfUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    private ContractType contractType;

    @Column(nullable = false)
    private boolean printable;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
