package sunhan.sunhanbackend.enums.approval;

public enum ApprovalAction {
    APPROVED,             // 승인
    REJECTED,             // 반려
    SKIPPED,              // 건너뜀
    FINAL_APPROVED,        // 전결 승인
    PENDING //대기 중 (아직 처리되지 않은 단계의 초기값으로 사용)
}