package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.common.ReadCache;
import com.jeffkm.mockleague.contest.TickPosition;
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
import java.util.Optional;
import java.io.Serializable;
import org.springframework.stereotype.Component;

/** 미리 만든 틱 가격 경로에서 가격을 읽는다(F5). */
@Component
public class TickPriceProvider implements PriceProvider {

  private final PriceTickRepository priceTickRepository;
  private final ContestRepository contestRepository;
  private final ContestListingRepository listingRepository;
  private final Clock clock;
  private final ReadCache readCache;

  public TickPriceProvider(PriceTickRepository priceTickRepository, ContestRepository contestRepository,
      ContestListingRepository listingRepository, Clock clock, ReadCache readCache) {
    this.priceTickRepository = priceTickRepository;
    this.contestRepository = contestRepository;
    this.listingRepository = listingRepository;
    this.clock = clock;
    this.readCache = readCache;
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
   *
   * <p>캐시: 키는 (대회, 날짜, 틱). 그 틱의 가격은 한 번 만들어지면 바뀌지 않으므로 무효화가 필요 없다.
   * 다만 그 틱의 가격이 아직 없어 예전 가격으로 채운 결과(complete=false)는 넣지 않는다.
   */
  @Override
  public Map<Long, Long> currentPrices(Long contestId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    Optional<TickPosition> at = contest.latestVisibleTick(LocalDateTime.now(clock));
    if (at.isEmpty()) {
      return basePrices(contestId);
    }
    TickPosition p = at.get();
    PriceBoard board = readCache.get(ReadCache.LATEST_PRICES, contestId + ":" + p.day() + ":" + p.tick(),
        () -> {
          HashMap<Long, Long> prices = basePrices(contestId);
          PriceTickRepository.LatestPrices latest = priceTickRepository.latestPricesUpTo(contestId, p);
          prices.putAll(latest.prices());
          return new PriceBoard(prices, latest.complete());
        },
        PriceBoard::complete);
    return new HashMap<>(board.prices()); // 부르는 쪽이 고쳐도 캐시 안의 값은 그대로이게 복사한다
  }

  /** 캐시에 넣는 값. Redis 는 객체를 바이트로 바꿔 저장하므로 Serializable 이어야 한다. */
  record PriceBoard(HashMap<Long, Long> prices, boolean complete) implements Serializable {}

  private HashMap<Long, Long> basePrices(Long contestId) {
    HashMap<Long, Long> prices = new HashMap<>();
    for (ContestListing l : listingRepository.findByIdContestId(contestId)) {
      prices.put(l.getId().getCompanyId(), l.getBasePrice());
    }
    return prices;
  }
}
