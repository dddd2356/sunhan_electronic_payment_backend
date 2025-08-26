package sunhan.sunhanbackend.enums;


public enum LeaveApplicationStatus {
    DRAFT,          // 임시 저장
    PENDING_SUBSTITUTE, // 대직자 승인 대기
    PENDING_DEPT_HEAD,  // 부서장 승인 대기
    PENDING_CENTER_DIRECTOR, // 진료센터장 승인 대기
    PENDING_HR_FINAL,        // 최종 인사팀 승인 대기
    PENDING_ADMIN_DIRECTOR,  // 행정원장 승인 대기
    PENDING_CEO_DIRECTOR,    // 대표원장 승인 대기
    PENDING_HR_STAFF,        // 인사팀 승인 대기 (필요시)
    APPROVED,       // 최종 승인
    REJECTED,       // 반려
    DELETED         // 삭제 처리됨 (실제 삭제 아님)
}