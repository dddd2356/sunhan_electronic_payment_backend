package sunhan.sunhanbackend.enums;

public enum PermissionType {
    HR_LEAVE_APPLICATION, // 휴가원 관련 인사 담당 권한
    HR_CONTRACT,        // 근로계약서 관련 인사 담당 권한
    MANAGE_USERS, //(권한 부여/회수 및 사용자 관리 권한)
    WORK_SCHEDULE_MANAGE, // 완료된 근무현황표 보기/수정/반려/인원 추가·삭제 (인사팀 전용)
    WORK_SCHEDULE_CREATE,  // 근무현황표 생성/작성/draft 수정/인원 추가·삭제/직책 생성

    // ✅ 전결 권한 추가
    FINAL_APPROVAL_LEAVE_APPLICATION,
    FINAL_APPROVAL_WORK_SCHEDULE,
    FINAL_APPROVAL_ALL
}
