package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestListing;
import com.jeffkm.mockleague.contest.ContestListingRepository;
import com.jeffkm.mockleague.contest.ContestRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대회의 가격 경로와 가상 공시를 "어느 날까지" 만들어 둔다(F4, F6).
 * 날짜를 건너뛸 수 없다: 다음 날 경로는 전날 종가에서 출발하므로 하루씩 차례로 만든다.
 * 여러 번 불러도 안전하다(이미 만든 날은 건너뛰고, 넣을 때도 ON CONFLICT DO NOTHING).
 */
@Service
public class PriceGenerationService {

  private static final Logger log = LoggerFactory.getLogger(PriceGenerationService.class);

  private final ContestRepository contestRepository;
  private final ContestListingRepository listingRepository;
  private final PriceTickRepository priceTickRepository;
  private final DisclosureRepository disclosureRepository;

  public PriceGenerationService(ContestRepository contestRepository, ContestListingRepository listingRepository,
      PriceTickRepository priceTickRepository, DisclosureRepository disclosureRepository) {
    this.contestRepository = contestRepository;
    this.listingRepository = listingRepository;
    this.priceTickRepository = priceTickRepository;
    this.disclosureRepository = disclosureRepository;
  }

  /**
   * through 날짜까지(대회 종료일을 넘지 않게) 아직 없는 날의 가격을 만든다.
   * 서버가 며칠 꺼져 있었어도 다음 실행 때 빠진 날을 차례로 채운다.
   *
   * @return 새로 만든 날 수
   */
  @Transactional
  public int ensureGeneratedThrough(Long contestId, LocalDate through) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    LocalDate last = through.isAfter(contest.getEndsOn()) ? contest.getEndsOn() : through;
    List<ContestListing> listings = listingRepository.findWithCompanyByContestId(contestId);
    List<DisclosurePlanner.Listed> listed = listings.stream()
        .map(l -> new DisclosurePlanner.Listed(l.getId().getCompanyId(), l.getCompany().getTicker(),
            l.getCompany().getName(), l.getCompany().getSector()))
        .toList();

    // 어디서부터 만들지: 만든 적이 없으면 첫날(전일 종가 = 상장 기준가), 있으면 마지막 날 다음 날(그날 종가에서 출발)
    LocalDate generatedUntil = priceTickRepository.lastGeneratedDay(contestId).orElse(null);
    LocalDate day;
    Map<Long, Long> previousClose;
    if (generatedUntil == null) {
      day = contest.getStartsOn();
      previousClose = listings.stream()
          .collect(Collectors.toMap(l -> l.getId().getCompanyId(), ContestListing::getBasePrice));
    } else {
      day = generatedUntil.plusDays(1);
      previousClose = priceTickRepository.closesOf(contestId, generatedUntil);
    }

    int created = 0;
    int ticks = contest.ticksPerDay();
    for (; !day.isAfter(last); day = day.plusDays(1)) {
      // 공시를 먼저 정하고, 그 충격을 넣어 가격을 만든다. 둘을 같은 트랜잭션에 넣는다:
      // 가격만 있고 공시가 없거나(설명 없는 급등), 공시만 있고 가격이 없는 날이 생기지 않게
      List<DisclosurePlanner.Planned> plans = DisclosurePlanner.planDay(contest.getSeed(), day, ticks, listed);
      Map<Long, long[]> paths = new HashMap<>();
      for (int i = 0; i < listings.size(); i++) {
        ContestListing listing = listings.get(i);
        Long companyId = listing.getId().getCompanyId();
        paths.put(companyId, PriceEngine.generateDay(contest.getSeed(), listing.getCompany().getTicker(), day,
            previousClose.get(companyId), listing.getCompany().getVolatility(),
            ticks, contest.getPriceLimitBp(), DisclosurePlanner.shocksFor(listed.get(i), plans, ticks)));
      }
      disclosureRepository.insertDay(contestId, day, plans);
      priceTickRepository.insertDay(contestId, day, paths);
      // 오늘의 종가 = 내일의 출발점
      Map<Long, Long> closes = new HashMap<>();
      paths.forEach((companyId, prices) -> closes.put(companyId, prices[prices.length - 1]));
      previousClose = closes;
      created++;
    }
    if (created > 0) {
      log.info("가격 생성: contest={}, {}일치, {}까지", contestId, created, last);
    }
    return created;
  }
}
