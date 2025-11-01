package sunhan.sunhanbackend.template;

import lombok.Getter;

@Getter
public enum NotificationTemplate {
    LEAVE_APPROVAL_REQUEST("LEAVE_APPROVAL_REQUEST",
            "🔔 [휴가 승인 요청] #{applicantName}님의 #{leaveType} 승인 요청이 도착했습니다.\n" +
                    "▶ 신청 정보\n" +
                    "👤 신청자: #{applicantName}\n" +
                    "🏢 부서: #{applicantDepartment}\n" +
                    "🏖️ 휴가 종류: #{leaveType}\n" +
                    "📅 휴가 기간: #{leaveStartDate} ~ #{leaveEndDate}\n" +
                    "📊 휴가 일수: #{leaveDays}일\n" +
                    "📝 신청 사유: #{leaveReason}\n" +
                    "💻 사내 시스템에서 승인 처리해 주세요."
    ),

    LEAVE_APPROVAL_COMPLETE("LEAVE_APPROVAL_COMPLETE",
            "🎉 [휴가 승인 완료] #{applicantName}님의 #{leaveType} 최종 승인이 완료되었습니다.\n" +
                    "▶ 승인 완료 내역\n" +
                    "👤 신청자: #{applicantName}\n" +
                    "📅 휴가 기간: #{leaveStartDate} ~ #{leaveEndDate}\n" +
                    "📊 휴가 일수: #{leaveDays}일\n" +
                    "✅ 최종 승인자: #{finalApproverName}\n" +
                    "📅 승인 일시: #{approvalDateTime}\n" +
                    "즐거운 휴가 보내세요! 😊"
    ),

    LEAVE_REJECTION("LEAVE_REJECTION",
            "⚠️ [휴가 반려 안내] #{applicantName}님의 #{leaveType} 신청이 반려되었습니다.\n" +
                    "▶ 반려 내역\n" +
                    "👤 신청자: #{applicantName}\n" +
                    "📅 신청 기간: #{leaveStartDate} ~ #{leaveEndDate}\n" +
                    "📊 신청 일수: #{leaveDays}일\n" +
                    "❌ 반려 사유: #{rejectionReason}\n" +
                    "👨‍💼 반려자: #{rejectorName}\n" +
                    "📅 반려 일시: #{rejectionDateTime}\n" +
                    "🔄 수정 후 재신청해 주세요."
    ),

    CONTRACT_SIGN_REQUEST("CONTRACT_SIGN_REQUEST",
            "📋 [근로계약서 서명 요청] #{employeeName}님의 근로계약서 서명이 필요합니다.\n" +
                    "▶ 계약 정보\n" +
                    "👤 직원명: #{employeeName}\n" +
                    "📋 계약 유형: #{contractType}\n" +
                    "🏢 부서: #{department}\n" +
                    "💼 직급: #{position}\n" +
                    "👨‍💼 작성자: #{creatorName}\n" +
                    "📅 계약 시작일: #{startDate}\n" +
                    "⏰ 서명 기한: #{signDeadline}\n" +
                    "💻 사내 전자서명 시스템에서 서명해 주세요."
    ),

    CONTRACT_SIGN_COMPLETE("CONTRACT_SIGN_COMPLETE",
            "🎉 [근로계약서 서명 완료] #{employeeName}님의 근로계약서 서명이 완료되었습니다.\n" +
                    "▶ 계약 체결 완료\n" +
                    "👤 직원명: #{employeeName}\n" +
                    "📋 계약 유형: #{contractType}\n" +
                    "📅 서명 완료일: #{signCompleteDate}\n" +
                    "📧 계약서 사본은 이메일로 발송됩니다."
    ),

    CONTRACT_REJECTION("CONTRACT_REJECTION",
            "⚠️ [근로계약서 반려 안내] #{employeeName}님의 계약서가 반려되었습니다.\n" +
                    "❌ 반려 사유: #{rejectionReason}\n" +
                    "👨‍💼 반려자: #{rejectorName}\n" +
                    "📅 반려 일시: #{rejectionDateTime}\n" +
                    "🔄 수정 후 재전송해주세요."
    ),

    PHONE_VERIFICATION("PHONE_VERIFICATION",
            "🔐 [전화번호 인증] #{userName}님의 전화번호 인증 코드입니다.\n" +
                    "▶ 인증번호: #{verificationCode}\n" +
                    "⏰ 5분 이내에 입력해 주세요. 타인에게 절대 알려주지 마세요."
    );

    private final String code;
    private final String template;

    NotificationTemplate(String code, String template) {
        this.code = code;
        this.template = template;
    }

    public static NotificationTemplate findByCode(String code) {
        for (NotificationTemplate t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return null;
    }
}