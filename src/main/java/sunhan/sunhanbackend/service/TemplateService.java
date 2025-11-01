package sunhan.sunhanbackend.service;

import org.springframework.stereotype.Service;
import sunhan.sunhanbackend.template.NotificationTemplate;

@Service
public class TemplateService {
    // enum 기반 조회
    public String getTemplateContent(String code, String channel, String locale) {
        // channel/locale 무시(단순화). 필요하면 enum에 channel 필드 추가.
        NotificationTemplate t = NotificationTemplate.findByCode(code);
        return t != null ? t.getTemplate() : null;
    }
}