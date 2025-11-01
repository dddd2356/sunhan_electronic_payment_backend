package sunhan.sunhanbackend.dto.approval;

import lombok.Data;
import sunhan.sunhanbackend.entity.mysql.approval.ApprovalLine;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class ApprovalLineResponseDto {
    private Long id;
    private String name;
    private String description;
    private DocumentType documentType;
    private String createdBy;
    private Boolean isActive;
    private List<ApprovalStepResponseDto> steps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ApprovalLineResponseDto fromEntity(ApprovalLine entity) {
        ApprovalLineResponseDto dto = new ApprovalLineResponseDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setDocumentType(entity.getDocumentType());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());

        if (entity.getSteps() != null) {
            dto.setSteps(entity.getSteps().stream()
                    .map(ApprovalStepResponseDto::fromEntity)
                    .collect(Collectors.toList()));
        }

        return dto;
    }
}