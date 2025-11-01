package sunhan.sunhanbackend.dto.approval;

import lombok.Data;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.util.List;

@Data
public class ApprovalLineCreateDto {
    private String name;
    private String description;
    private DocumentType documentType;
    private List<ApprovalStepDto> steps;
}
