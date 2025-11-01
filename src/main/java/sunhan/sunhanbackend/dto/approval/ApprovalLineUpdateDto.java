package sunhan.sunhanbackend.dto.approval;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sunhan.sunhanbackend.enums.approval.DocumentType;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ApprovalLineUpdateDto {
    private String name;
    private String description;
    private DocumentType documentType;
    private Boolean isActive;
    private List<ApprovalStepDto> steps;
}
