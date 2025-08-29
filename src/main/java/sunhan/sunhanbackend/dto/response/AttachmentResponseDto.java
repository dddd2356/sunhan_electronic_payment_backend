package sunhan.sunhanbackend.dto.response;

import lombok.Builder;
import lombok.Getter;
import sunhan.sunhanbackend.entity.mysql.LeaveApplicationAttachment;

@Getter
public class AttachmentResponseDto {
    private Long id;
    private String originalFileName;
    private String fileType;
    private long fileSize;

    @Builder
    public AttachmentResponseDto(Long id, String originalFileName, String fileType, long fileSize) {
        this.id = id;
        this.originalFileName = originalFileName;
        this.fileType = fileType;
        this.fileSize = fileSize;
    }

    public static AttachmentResponseDto fromEntity(LeaveApplicationAttachment entity) {
        return AttachmentResponseDto.builder()
                .id(entity.getId())
                .originalFileName(entity.getOriginalFileName())
                .fileType(entity.getFileType())
                .fileSize(entity.getFileSize())
                .build();
    }
}