package sunhan.sunhanbackend.enums.approval;

public enum ApprovalProcessStatus {
    DRAFT,                // 임시저장
    IN_PROGRESS,          // 진행 중
    APPROVED,             // 승인 완료
    REJECTED,             // 반려됨
    CANCELLED             // 취소됨
}