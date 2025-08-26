package sunhan.sunhanbackend.enums;

public enum ContractStatus {
    DRAFT,               // 관리자 작성 중
    SENT_TO_EMPLOYEE,    // 직원에게 전송됨
    SIGNED_BY_EMPLOYEE,  // 직원 서명 완료
    RETURNED_TO_ADMIN,   // 직원이 반려(사유 포함)
    COMPLETED,           // PDF 생성 완료, 수정·재전송 불가
    DELETED
}