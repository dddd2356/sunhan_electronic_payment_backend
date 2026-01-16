package sunhan.sunhanbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.consent.ConsentAgreement;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.repository.mysql.EmploymentContractRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.consent.ConsentAgreementRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.workschedule.WorkScheduleService;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final FormService formService;
    private final EmploymentContractRepository contractRepository;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final WorkScheduleService workScheduleService;
    private final WorkScheduleRepository scheduleRepository;
    private final ConsentAgreementRepository agreementRepository;

    @Value("${holiday.api.key}")
    private String holidayApiKey;

    @Value("${holiday.api.url}")
    private String holidayApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async // 이 메서드를 비동기 백그라운드 스레드에서 실행
    // 트랜잭션을 분리하여 원래 요청의 트랜잭션과 독립적으로 실행
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateContractPdfAsync(Long contractId) {
        try {
            log.info("[Async] 근로계약서 PDF 생성 시작: id={}", contractId);
            EmploymentContract contract = contractRepository.findById(contractId)
                    .orElseThrow(() -> new RuntimeException("비동기 PDF 생성 실패: 계약서 없음"));

            // PDF 생성 및 저장
            String pdfUrl = formService.generatePdf(contract);

            // PDF URL 및 인쇄 가능 상태 업데이트
            contract.setPdfUrl(pdfUrl);
            contract.setPrintable(true);
            contractRepository.save(contract);
            log.info("[Async] 근로계약서 PDF 생성 및 저장 완료: id={}", contractId);
        } catch (Exception e) {
            log.error("[Async] 근로계약서 PDF 생성 중 오류 발생: id={}", contractId, e);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateLeaveApplicationPdfAsync(Long applicationId) {
        try {
            log.info("[Async] 휴가신청서 PDF 생성 시작: id={}", applicationId);
            LeaveApplication application = leaveApplicationRepository.findById(applicationId)
                    .orElseThrow(() -> new RuntimeException("비동기 PDF 생성 실패: 휴가신청서 없음"));

            // PDF 생성 및 저장
            String pdfUrl = formService.savePdf(application);

            // PDF URL 업데이트 (printable은 이미 이전 단계에서 true가 됨)
            application.setPdfUrl(pdfUrl);
            leaveApplicationRepository.save(application);
            log.info("[Async] 휴가신청서 PDF 생성 및 저장 완료: id={}", applicationId);
        } catch (Exception e) {
            log.error("[Async] 휴가신청서 PDF 생성 중 오류 발생: id={}", applicationId, e);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateWorkSchedulePdfAsync(Long scheduleId) {
        log.info(">>>> [Async Start] 근무표 PDF 생성 작업 시작: id={}", scheduleId);
        try {
            log.info("[Async] 근무표 PDF 생성 시작: id={}", scheduleId);
            WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new RuntimeException("비동기 PDF 생성 실패: 근무표 없음"));

            // ✅ 공휴일 데이터 조회 추가
            String yearMonth = schedule.getScheduleYearMonth();
            Set<String> holidays = loadHolidaysForYear(yearMonth);
            log.info("공휴일 데이터 로드 완료: yearMonth={}, count={}", yearMonth, holidays.size());

            Map<String, Object> scheduleDetail = workScheduleService.getScheduleDetail(scheduleId, schedule.getCreatedBy());

            // 추가: scheduleDetail 검증 (entries가 비었는지 확인)
            if (scheduleDetail == null || scheduleDetail.isEmpty()) {
                throw new RuntimeException("scheduleDetail 데이터가 비어있습니다.");
            }

            // ✅ 공휴일 데이터 추가
            scheduleDetail.put("holidays", holidays);

            log.info("scheduleDetail 데이터 확인: entries 개수 = {}", ((List<?>) scheduleDetail.getOrDefault("entries", new ArrayList<>())).size());

            // PDF 생성 (기존 파일 자동 삭제 포함)
            String pdfUrl = formService.saveWorkSchedulePdf(schedule, scheduleDetail);

            schedule.setPdfUrl(pdfUrl);
            scheduleRepository.save(schedule);
            log.info("[Async] 근무표 PDF 생성 및 저장 완료: id={}, pdfUrl={}", scheduleId, pdfUrl);  // 성공 로그 추가
        } catch (Exception e) {
            log.error("[Async] 근무표 PDF 생성 중 오류 발생: id={}", scheduleId, e);  // 상세 에러 로그
            // ✅ 실패 시 DB 정리
            WorkSchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
            if (schedule != null) {
                schedule.setPdfUrl(null);
                scheduleRepository.save(schedule);
            }
        }
    }

    /**
     * 공휴일 조회 (API 연동)
     */
    private Set<String> loadHolidaysForYear(String yearMonth) {
        Set<String> holidaySet = new HashSet<>();

        try {
            String[] parts = yearMonth.split("-");
            int year = Integer.parseInt(parts[0]);

            String url = String.format(
                    "%s?serviceKey=%s&solYear=%d&numOfRows=100&_type=json",
                    holidayApiUrl, holidayApiKey, year
            );

            String response = restTemplate.getForObject(url, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    String locdate = item.path("locdate").asText();
                    if (locdate.length() == 8) {
                        String month = locdate.substring(4, 6);
                        String day = locdate.substring(6, 8);
                        holidaySet.add(Integer.parseInt(month) + "-" + Integer.parseInt(day));
                    }
                }
            } else if (items.isObject()) {
                String locdate = items.path("locdate").asText();
                if (locdate.length() == 8) {
                    String month = locdate.substring(4, 6);
                    String day = locdate.substring(6, 8);
                    holidaySet.add(Integer.parseInt(month) + "-" + Integer.parseInt(day));
                }
            }

            log.info("공휴일 API 조회 완료: year={}, count={}", year, holidaySet.size());

        } catch (Exception e) {
            log.warn("공휴일 API 조회 실패, 기본 공휴일 사용: yearMonth={}", yearMonth, e);
            return getDefaultHolidays(yearMonth);
        }

        return holidaySet;
    }

    /**
     * 기본 공휴일 (API 실패 시 사용)
     */
    private Set<String> getDefaultHolidays(String yearMonth) {
        Set<String> holidaySet = new HashSet<>();
        String[] parts = yearMonth.split("-");
        int month = Integer.parseInt(parts[1]);

        if (month == 1) holidaySet.add("1-1");
        if (month == 3) holidaySet.add("3-1");
        if (month == 5) holidaySet.add("5-5");
        if (month == 6) holidaySet.add("6-6");
        if (month == 8) holidaySet.add("8-15");
        if (month == 10) {
            holidaySet.add("10-3");
            holidaySet.add("10-9");
        }
        if (month == 12) holidaySet.add("12-25");

        log.info("기본 공휴일 사용: yearMonth={}, count={}", yearMonth, holidaySet.size());
        return holidaySet;
    }

    /**
     * 동의서 PDF 생성 (비동기)
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateConsentPdf(ConsentAgreement agreement) {
        try {
            log.info("동의서 PDF 생성 시작: agreementId={}", agreement.getId());

            // FormService를 통해 HTML을 PDF로 변환하고 저장된 URL을 받아옴
            String pdfUrl = formService.generateConsentPdf(agreement);

            // PDF 경로 업데이트
            agreement.setPdfUrl(pdfUrl);
            agreementRepository.save(agreement);

            log.info("동의서 PDF 생성 완료: URL={}", pdfUrl);
        } catch (Exception e) {
            log.error("동의서 PDF 생성 중 오류 발생: id={}", agreement.getId(), e);

            // 실패 시 pdfUrl을 null로 설정 (재시도 가능하도록)
            agreement.setPdfUrl(null);
            agreementRepository.save(agreement);
        }
    }
}