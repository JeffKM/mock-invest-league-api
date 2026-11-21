package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.alert.AlertNotifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 진행 중인 대회의 불변식을 검사하고, 어긋나면 운영자에게 알린다(7주차②).
 *
 * <p>6주차③의 불변식 검사는 관리자 API 를 누가 불러야만 돌았다. 부하 테스트 뒤에는 불렀지만, 평소 운영 중에는
 * 돈이 한 원 어긋나도 아무도 모른다. 그래서 장 마감 뒤 배치가 끝나면 스스로 검사하고, 위반이 있을 때만 알린다.
 */
@Service
public class InvariantAuditJob {

  private static final Logger log = LoggerFactory.getLogger(InvariantAuditJob.class);

  private final ContestRepository contestRepository;
  private final InvariantAuditService auditService;
  private final AlertNotifier notifier;

  public InvariantAuditJob(ContestRepository contestRepository, InvariantAuditService auditService,
      AlertNotifier notifier) {
    this.contestRepository = contestRepository;
    this.auditService = auditService;
    this.notifier = notifier;
  }

  /** 진행 중인 모든 대회를 검사한다. 위반이 있는 대회만 알림을 보낸다. 검사 결과를 돌려준다. */
  public List<InvariantAuditService.Report> auditRunning() {
    List<InvariantAuditService.Report> reports = new ArrayList<>();
    for (Contest c : contestRepository.findByStatus(ContestStatus.RUNNING)) {
      InvariantAuditService.Report report;
      try {
        report = auditService.audit(c.getId());
      } catch (RuntimeException e) {
        log.error("불변식 검사 실패: contest={}", c.getId(), e);
        notifier.send("[league] 불변식 검사 자체가 실패: 대회 " + c.getId() + " (" + e.getClass().getSimpleName() + ")");
        continue;
      }
      reports.add(report);
      if (!report.ok()) {
        notifier.send(message(report));
      }
    }
    return reports;
  }

  static String message(InvariantAuditService.Report report) {
    String broken = report.checks().stream()
        .filter(c -> c.violations() > 0)
        .map(c -> "- " + c.code() + " " + c.violations() + "건 (예: " + c.sample() + ")")
        .collect(Collectors.joining("\n"));
    return "[league] 불변식 위반: 대회 " + report.contestId() + "\n" + broken;
  }
}
