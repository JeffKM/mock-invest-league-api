package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.account.dto.AttendanceResponse;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출석 보너스(F11): 진행 중인 대회의 거래일에 참가자당 하루 한 번 현금을 준다.
 * "하루"는 한국 시간 날짜다. 운영 Clock 빈이 Asia/Seoul 이라 LocalDate.now(clock) 이 곧 한국 날짜다
 * (서버 기본 시간대가 UTC 여도 자정~09시 사이에 날짜가 어긋나지 않는다).
 */
@Service
public class AttendanceService {

  private final ContestRepository contestRepository;
  private final ParticipantRepository participantRepository;
  private final AttendanceRepository attendanceRepository;
  private final CashLedgerRepository ledgerRepository;
  private final Clock clock;

  public AttendanceService(ContestRepository contestRepository, ParticipantRepository participantRepository,
      AttendanceRepository attendanceRepository, CashLedgerRepository ledgerRepository, Clock clock) {
    this.contestRepository = contestRepository;
    this.participantRepository = participantRepository;
    this.attendanceRepository = attendanceRepository;
    this.ledgerRepository = ledgerRepository;
    this.clock = clock;
  }

  /**
   * 출석 기록, 현금 입금, BONUS 원장을 한 트랜잭션으로 남긴다.
   * 셋 중 하나만 남으면 I5(현금 = 원장 합계)나 I7(하루 한 번)이 깨진다.
   */
  @Transactional
  public AttendanceResponse attend(Long contestId, Long userId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    LocalDate today = LocalDate.now(clock);
    if (!contest.isRunning() || !contest.isTradingDay(today)) {
      throw new BusinessException(ErrorCode.CONTEST_NOT_RUNNING);
    }
    // 현금을 바꾸므로 주문과 같은 순서(계좌 잠금 먼저)로 잠근다. 잠금 순서가 같으면 교착이 생기지 않는다
    Participant participant = participantRepository.findForUpdate(contestId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));
    // 잠금이 있어도 판정은 DB 기본키에 맡긴다. 잠금 전략을 바꾸거나 다른 경로가 생겨도 I7 은 지켜진다
    if (!attendanceRepository.insertIfAbsent(participant.getId(), today)) {
      throw new BusinessException(ErrorCode.ALREADY_ATTENDED);
    }
    long bonus = contest.getAttendanceBonus();
    if (bonus > 0) { // 보너스 0원 대회도 출석은 남긴다. 원장은 0원 줄을 받지 않는다
      participant.deposit(bonus);
      ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.BONUS, bonus, null));
    }
    return new AttendanceResponse(today, bonus, participant.getCash());
  }
}
