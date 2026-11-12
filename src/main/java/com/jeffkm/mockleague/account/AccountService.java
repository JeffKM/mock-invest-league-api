package com.jeffkm.mockleague.account;

import com.jeffkm.mockleague.account.dto.AccountResponse;
import com.jeffkm.mockleague.account.dto.JoinResponse;
import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.market.PriceProvider;
import com.jeffkm.mockleague.user.User;
import com.jeffkm.mockleague.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대회 참가와 내 계좌 조회. */
@Service
public class AccountService {

  private final ContestRepository contestRepository;
  private final UserRepository userRepository;
  private final ParticipantRepository participantRepository;
  private final HoldingRepository holdingRepository;
  private final CashLedgerRepository ledgerRepository;
  private final PriceProvider priceProvider;

  public AccountService(ContestRepository contestRepository, UserRepository userRepository,
      ParticipantRepository participantRepository, HoldingRepository holdingRepository,
      CashLedgerRepository ledgerRepository, PriceProvider priceProvider) {
    this.contestRepository = contestRepository;
    this.userRepository = userRepository;
    this.participantRepository = participantRepository;
    this.holdingRepository = holdingRepository;
    this.ledgerRepository = ledgerRepository;
    this.priceProvider = priceProvider;
  }

  /**
   * 참가: 계좌를 만들고 초기 자금을 INITIAL 원장 한 줄로 남긴다.
   * 두 저장은 한 트랜잭션이어야 한다. 계좌만 생기고 원장이 빠지면 I5(현금 = 원장 합계)가 처음부터 깨진다.
   */
  @Transactional
  public JoinResponse join(Long contestId, Long userId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    if (!contest.isJoinable()) {
      throw new BusinessException(ErrorCode.CONTEST_NOT_JOINABLE);
    }
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    // 순차 중복은 여기서, 동시 중복은 DB 유니크 (contest_id, user_id) 가 막는다
    if (participantRepository.findByContestIdAndUserId(contestId, userId).isPresent()) {
      throw new BusinessException(ErrorCode.ALREADY_JOINED);
    }

    Participant participant = participantRepository.save(Participant.join(contest, user));
    ledgerRepository.save(
        CashLedger.record(participant, LedgerEntryType.INITIAL, participant.getCash(), null));
    return new JoinResponse(participant.getId(), contestId, participant.getCash());
  }

  /** 내 계좌: 현금과 보유 종목을 현재가로 평가한다. 읽기만 하므로 readOnly(더티 체킹 생략). */
  @Transactional(readOnly = true)
  public AccountResponse getMyAccount(Long contestId, Long userId) {
    Participant p = participantRepository.findByContestIdAndUserId(contestId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));
    // 가격은 종목마다 묻지 않고 대회 전체를 한 번에 가져온다(쿼리 1번)
    Map<Long, Long> prices = priceProvider.currentPrices(contestId);

    List<AccountResponse.Position> positions = holdingRepository.findOpenPositionsWithCompany(p.getId())
        .stream()
        .map(h -> {
          long price = prices.get(h.getCompany().getId());
          long value = Math.multiplyExact(price, h.getQuantity());
          return new AccountResponse.Position(
              h.getCompany().getTicker(), h.getCompany().getName(), h.getQuantity(),
              h.averagePrice(), price, value, value - h.getTotalCost());
        })
        .toList();

    long holdingsValue = positions.stream().mapToLong(AccountResponse.Position::marketValue).sum();
    long total = p.getCash() + p.getReservedCash() + holdingsValue;
    return new AccountResponse(p.getId(), p.getCash(), p.getReservedCash(), holdingsValue, total, positions);
  }
}
