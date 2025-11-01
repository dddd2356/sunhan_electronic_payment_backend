package sunhan.sunhanbackend.enums;


public enum LeaveApplicationStatus {
    DRAFT,              // 임시 저장
    PENDING,            // 승인 대기 (결재라인 진행 중)
    APPROVED,           // 최종 승인
    REJECTED,           // 반려
    DELETED;            // 삭제 처리됨

    /**
     * 승인 대기 상태인지 확인
     */
    public boolean isPending() {
        return this == PENDING;
    }
}