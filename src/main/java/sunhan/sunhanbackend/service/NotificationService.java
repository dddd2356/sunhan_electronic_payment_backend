package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import sunhan.sunhanbackend.dto.response.NotificationResult;
import sunhan.sunhanbackend.entity.mysql.UserEntity;
import sunhan.sunhanbackend.enums.NotificationChannel;
import sunhan.sunhanbackend.repository.mysql.UserRepository;

import java.time.Instant;
import java.util.Map;

@Service
@Slf4j
public class NotificationService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TemplateService templateService;

    // Common
    @Value("${aligo.api.key}")
    private String apiKey;
    @Value("${aligo.api.user_id}")
    private String apiUserId;
    @Value("${aligo.sender.phone}")
    private String senderPhone;

    // Alimtalk
    @Value("${aligo.sender.key}")
    private String alimtalkSenderKey;
    @Value("${aligo.token.url}")
    private String tokenUrl;
    @Value("${aligo.alimtalk.send.url}")
    private String alimtalkSendUrl;

    // SMS
    @Value("${aligo.sms.send.url}")
    private String smsSendUrl;

    // Alimtalk Token Cache
    private String alimtalkToken;
    private long tokenExpiryTime;

    /**
     * 알림톡 토큰 발급 (캐싱) - NPE 방지 및 에러 처리 강화
     */
    private void generateAlimtalkToken() {
        if (alimtalkToken != null && Instant.now().getEpochSecond() < tokenExpiryTime) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("apikey", apiKey);
            body.add("userid", apiUserId);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

            String respBody = response.getBody();
            if (respBody == null) {
                log.error("알림톡 토큰 발급 응답이 null입니다.");
                return;
            }

            Map<String, Object> responseMap = objectMapper.readValue(respBody, new TypeReference<>() {});

            // ⚠️ 중요: NPE 방지 - responseMap.get("code")가 null일 수 있음
            Object codeObj = responseMap.get("code");
            if (codeObj == null) {
                log.error("알림톡 토큰 응답에 code가 없습니다: {}", respBody);
                return;
            }

            String code = String.valueOf(codeObj);

            if ("0".equals(code) || "200".equals(code)) {
                Object tokenObj = responseMap.get("token");
                if (tokenObj == null) {
                    log.error("알림톡 토큰 응답에 token이 없습니다: {}", respBody);
                    return;
                }

                this.alimtalkToken = String.valueOf(tokenObj);
                long expiresIn = responseMap.containsKey("expires_in")
                        ? Long.parseLong(responseMap.get("expires_in").toString())
                        : 29L * 24 * 60 * 60;
                this.tokenExpiryTime = Instant.now().getEpochSecond() + expiresIn - 60;
                log.info("알림톡 토큰 발급 성공");
            } else {
                String message = responseMap.get("message") != null ?
                        String.valueOf(responseMap.get("message")) : "알 수 없는 오류";
                log.error("알림톡 토큰 발급 실패. 코드: {}, 메시지: {}", code, message);
            }
        } catch (Exception e) {
            log.error("알림톡 토큰 발급 중 예외 발생", e);
        }
    }

    /**
     * 외부에서 호출하는 메인 엔드포인트
     */
    public NotificationResult sendNotification(NotificationChannel channel,
                                               String receiver,
                                               String templateCode,
                                               Map<String, String> variables) {
        if (receiver == null || receiver.isBlank()) {
            return NotificationResult.fail(channel, receiver, templateCode, "수신자 정보가 비어있습니다.");
        }
        String normalizedPhone = normalizePhone(receiver);

        UserEntity user = userRepository.findByPhone(normalizedPhone).orElse(null);
        if (user == null) {
            return NotificationResult.fail(channel, normalizedPhone, templateCode, "사용자를 찾을 수 없습니다.");
        }
        if (!Boolean.TRUE.equals(user.getNotificationConsent())) {
            return NotificationResult.fail(channel, normalizedPhone, templateCode, "알림 수신 동의가 되어있지 않습니다.");
        }

        return switch (channel) {
            case KAKAO -> sendAlimtalk(normalizedPhone, templateCode, variables);
            case SMS -> sendSms(normalizedPhone, templateCode, variables);
        };
    }

    /**
     * 알림톡 발송 - 실제 성공/실패 판단 로직 추가
     */
    private NotificationResult sendAlimtalk(String receiver, String templateCode, Map<String, String> variables) {
        generateAlimtalkToken();
        if (this.alimtalkToken == null) {
            return NotificationResult.fail(NotificationChannel.KAKAO, receiver, templateCode, "알림톡 토큰이 없습니다.");
        }

        String templateText = templateService.getTemplateContent(templateCode, "kakao", "ko");
        if (templateText == null || templateText.isBlank()) {
            return NotificationResult.fail(NotificationChannel.KAKAO, receiver, templateCode, "템플릿을 찾을 수 없습니다.");
        }
        String messageContent = replaceTemplateVariables(templateText, variables);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("apikey", apiKey);
        body.add("userid", apiUserId);
        body.add("token", this.alimtalkToken);
        body.add("senderkey", alimtalkSenderKey);
        body.add("tpl_code", templateCode);
        body.add("sender", senderPhone);
        body.add("receiver_1", receiver);
        body.add("subject_1", getSubjectForTemplate(templateCode));
        body.add("message_1", messageContent);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(alimtalkSendUrl, new HttpEntity<>(body, headers), String.class);

            String responseBody = response.getBody();
            log.info("알림톡 발송 응답: {}", responseBody);

            // ⚠️ 중요: HTTP 200이어도 실제로는 실패일 수 있음 - 응답 내용 확인 필수
            if (responseBody != null && isAlimtalkSuccess(responseBody)) {
                return NotificationResult.success(NotificationChannel.KAKAO, receiver, templateCode, messageContent, responseBody);
            } else {
                return NotificationResult.fail(NotificationChannel.KAKAO, receiver, templateCode,
                        "알림톡 발송 실패: " + responseBody);
            }

        } catch (HttpClientErrorException e) {
            log.error("알림톡 발송 HTTP 오류: {}", e.getResponseBodyAsString());
            return NotificationResult.fail(NotificationChannel.KAKAO, receiver, templateCode,
                    "HTTP 오류: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("알림톡 발송 중 예외 발생", e);
            return NotificationResult.fail(NotificationChannel.KAKAO, receiver, templateCode,
                    "발송 중 오류: " + e.getMessage());
        }
    }

    /**
     * ✅ 새로 추가: 템플릿 코드에 따른 제목 반환
     */
    private String getSubjectForTemplate(String templateCode) {
        return switch (templateCode) {
            case "LEAVE_APPROVAL_REQUEST" -> "휴가 승인 요청";
            case "LEAVE_APPROVAL_COMPLETE" -> "휴가 승인 완료";
            case "LEAVE_REJECTION" -> "휴가 반려 안내";
            case "CONTRACT_SIGN_REQUEST" -> "근로계약서 서명 요청";
            case "CONTRACT_SIGN_COMPLETE" -> "근로계약서 서명 완료";
            case "CONTRACT_REJECTION" -> "근로계약서 반려 안내";
            //case "PHONE_VERIFICATION" -> "전화번호 인증";
            default -> "시스템 알림";
        };
    }

    /**
     * SMS 발송 - 실제 성공/실패 판단 로직 추가
     */
    private NotificationResult sendSms(String receiver, String templateCode, Map<String, String> variables) {
        String templateText = templateService.getTemplateContent(templateCode, "sms", "ko");
        if (templateText == null || templateText.isBlank()) {
            return NotificationResult.fail(NotificationChannel.SMS, receiver, templateCode, "SMS 템플릿을 찾을 수 없습니다.");
        }
        String messageContent = replaceTemplateVariables(templateText, variables);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("key", apiKey);
        body.add("user_id", apiUserId);
        body.add("sender", senderPhone);
        body.add("receiver", receiver);
        body.add("msg", messageContent);
        body.add("msg_type", messageContent.length() > 90 ? "LMS" : "SMS");

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(smsSendUrl, new HttpEntity<>(body, headers), String.class);

            String responseBody = response.getBody();
            log.info("SMS 발송 응답: {}", responseBody);

            // ⚠️ 중요: HTTP 200이어도 실제로는 실패일 수 있음 - 응답 내용 확인 필수
            if (responseBody != null && isSmsSuccess(responseBody)) {
                return NotificationResult.success(NotificationChannel.SMS, receiver, templateCode, messageContent, responseBody);
            } else {
                return NotificationResult.fail(NotificationChannel.SMS, receiver, templateCode,
                        "SMS 발송 실패: " + responseBody);
            }

        } catch (HttpClientErrorException e) {
            log.error("SMS 발송 HTTP 오류: {}", e.getResponseBodyAsString());
            return NotificationResult.fail(NotificationChannel.SMS, receiver, templateCode,
                    "HTTP 오류: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("SMS 발송 중 예외 발생", e);
            return NotificationResult.fail(NotificationChannel.SMS, receiver, templateCode,
                    "발송 중 오류: " + e.getMessage());
        }
    }

    /**
     * ⚠️ 새로 추가: 알림톡 응답의 실제 성공 여부 판단
     */
    private boolean isAlimtalkSuccess(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, new TypeReference<>() {});
            Object codeObj = response.get("code");
            if (codeObj == null) return false;

            // 알림톡 성공 코드: 0
            String code = String.valueOf(codeObj);
            return "0".equals(code);
        } catch (Exception e) {
            log.warn("알림톡 응답 파싱 실패: {}", responseBody);
            return false;
        }
    }

    /**
     * ⚠️ 새로 추가: SMS 응답의 실제 성공 여부 판단
     */
    private boolean isSmsSuccess(String responseBody) {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, new TypeReference<>() {});
            Object resultCodeObj = response.get("result_code");
            if (resultCodeObj == null) return false;

            // SMS 성공 코드: "1"
            String resultCode = String.valueOf(resultCodeObj);
            return "1".equals(resultCode);
        } catch (Exception e) {
            log.warn("SMS 응답 파싱 실패: {}", responseBody);
            // JSON 파싱 실패 시 문자열 패턴으로 확인
            return responseBody.contains("success") || responseBody.contains("\"result_code\":\"1\"");
        }
    }

    /**
     * 템플릿 변수 치환
     */
    private String replaceTemplateVariables(String template, Map<String, String> variables) {
        if (template == null) return "";
        if (variables == null || variables.isEmpty()) return template;

        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "#{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }

        // ⚠️ 치환되지 않은 변수 확인 (디버깅용)
        if (result.contains("#{")) {
            log.warn("치환되지 않은 템플릿 변수가 있습니다: {}", result);
        }

        return result;
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String normalized = raw.replaceAll("[^0-9]", "");

        // ⚠️ 전화번호 형식 검증 추가
        if (!normalized.matches("^(010|011|016|017|018|019)\\d{7,8}$")) {
            log.warn("유효하지 않은 전화번호 형식: {} -> {}", raw, normalized);
        }

        return normalized;
    }
}