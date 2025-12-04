package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sunhan.sunhanbackend.entity.mysql.EmploymentContract;
import sunhan.sunhanbackend.entity.mysql.LeaveApplication;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkSchedule;
import sunhan.sunhanbackend.entity.mysql.workschedule.WorkScheduleEntry;
import sunhan.sunhanbackend.repository.mysql.EmploymentContractRepository;
import sunhan.sunhanbackend.repository.mysql.LeaveApplicationRepository;
import sunhan.sunhanbackend.repository.mysql.workschedule.WorkScheduleRepository;
import sunhan.sunhanbackend.service.workschedule.WorkScheduleService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final FormService formService;
    private final EmploymentContractRepository contractRepository;
    private final LeaveApplicationRepository leaveApplicationRepository;
    private final WorkScheduleService workScheduleService;
    private final WorkScheduleRepository scheduleRepository;

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
            Map<String, Object> scheduleDetail = workScheduleService.getScheduleDetail(scheduleId, schedule.getCreatedBy());

            // 추가: scheduleDetail 검증 (entries가 비었는지 확인)
            if (scheduleDetail == null || scheduleDetail.isEmpty()) {
                throw new RuntimeException("scheduleDetail 데이터가 비어있습니다.");
            }
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
}