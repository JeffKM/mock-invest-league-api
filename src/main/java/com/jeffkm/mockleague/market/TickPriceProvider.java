package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestListing;
import com.jeffkm.mockleague.contest.ContestListingId;
import com.jeffkm.mockleague.contest.ContestListingRepository;
import com.jeffkm.mockleague.contest.ContestRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 미리 만든 틱 가격 경로에서 가격을 읽는다(F5). */
@Component
public class TickPriceProvider implements PriceProvider {

  private final PriceTickRepository priceTickRepository;
  private final ContestRepository contestRepository;
  private final ContestListingRepository listingRepository;
  private final Clock clock;

  public TickPriceProvider(PriceTickRepository priceTickRepository, ContestRepository contestRepository,
      ContestListingRepository listingRepository, Clock clock) {
    this.priceTickRepository = priceTickRepository;
    this.contestRepository = contestRepository;
    this.listingRepository = listingRepository;
    this.clock = clock;
  }

  @Override
  public long priceAt(Long contestId, Long companyId, LocalDate day, int tick) {
    return priceTickRepository.priceAt(contestId, companyId, day, tick).orElseThrow(() -> {
      // 가격이 없다: 상장 안 된 종목(클라이언트 잘못)인지, 가격 생성이 밀린 것(서버 사정)인지 구분해 알린다
      if (!listingRepository.existsById(new ContestListingId(contestId, companyId))) {
        return new BusinessException(ErrorCode.NOT_FOUND, "이 대회에 상장되지 않은 종목입니다.");
      }
      return new BusinessException(ErrorCode.PRICE_NOT_READY);
    });
  }

  /**
   * 상장 기준가로 먼저 채우고, 지금 볼 수 있는 틱 가격으로 덮는다.
   * 대회 시작 전이나 첫날 장 시작 전에는 틱 가격이 없으므로 기준가가 그대로 남는다.
   */
  @Override
  public Map<Long, Long> currentPrices(Long contestId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    Map<Long, Long> prices = new HashMap<>();
    for (ContestListing l : listingRepository.findByIdContestId(contestId)) {
      prices.put(l.getId().getCompanyId(), l.getBasePrice());
    }
    LocalDateTime now = LocalDateTime.now(clock);
    contest.latestVisibleTick(now)
        .ifPresent(at -> prices.putAll(priceTickRepository.latestPricesUpTo(contestId, at)));
    return prices;
  }
}
