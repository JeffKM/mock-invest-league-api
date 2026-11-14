package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.dto.ContestResponse;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import com.jeffkm.mockleague.market.PriceGenerationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대회 관리(관리자용). 인증 없이 열어 둔 상태다(인증은 뒤에서).
 * 상태 전환은 일일 배치(ContestLifecycleService)가 날짜를 보고 하고, 관리자가 수동으로 시작할 수도 있다.
 */
@Service
public class ContestService {

  private final ContestRepository contestRepository;
  private final ContestListingRepository listingRepository;
  private final CompanyRepository companyRepository;
  private final PriceGenerationService priceGenerationService;
  private final Clock clock;
  private final JdbcTemplate jdbc;

  public ContestService(ContestRepository contestRepository, ContestListingRepository listingRepository,
      CompanyRepository companyRepository, PriceGenerationService priceGenerationService, Clock clock,
      JdbcTemplate jdbc) {
    this.contestRepository = contestRepository;
    this.listingRepository = listingRepository;
    this.companyRepository = companyRepository;
    this.priceGenerationService = priceGenerationService;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  /** 기본 규칙으로 대회를 만들고 상장사 전체를 상장한다. 대회와 상장 목록은 한 트랜잭션: 반쪽짜리 대회가 남지 않는다. */
  @Transactional
  public ContestResponse create(CreateContestRequest request) {
    if (request.startsOn().isAfter(request.endsOn())) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작일이 종료일보다 늦을 수 없습니다.");
    }
    long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong();
    Contest contest = contestRepository.save(
        Contest.withDefaults(request.name(), request.startsOn(), request.endsOn(), seed));
    List<ContestListing> listings = companyRepository.findAll().stream()
        .map(company -> ContestListing.of(contest, company))
        .toList();
    listingRepository.saveAll(listings);
    return ContestResponse.from(contest, listings.size());
  }

  /**
   * SCHEDULED → RUNNING. 이미 시작했거나 끝난 대회면 409.
   * 시작과 함께 "오늘(기간 안으로 맞춘 날)"까지의 가격을 만든다. 시작했는데 가격이 없으면 주문을 받을 수 없다.
   * 상태 변경과 가격 생성은 한 트랜잭션: 가격 생성이 실패하면 시작도 취소된다.
   */
  @Transactional
  public ContestResponse start(Long contestId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    if (contest.getStatus() != ContestStatus.SCHEDULED) {
      throw new BusinessException(ErrorCode.CONTEST_NOT_JOINABLE, "예정 상태의 대회만 시작할 수 있습니다.");
    }
    contest.start(); // 더티 체킹으로 커밋 때 UPDATE
    LocalDate today = LocalDate.now(clock);
    LocalDate through = today.isBefore(contest.getStartsOn()) ? contest.getStartsOn() : today;
    priceGenerationService.ensureGeneratedThrough(contestId, through); // 종료일을 넘으면 안에서 잘라 낸다
    // 6주차⑦ P6: 새 대회의 상장 24줄은 자동 ANALYZE 기준(50줄 + 10%)에 못 미쳐 통계에 없다. 그러면 플래너가 이 대회의
    // 종목을 1개로 예상해 순위 스냅샷에서 보유 행마다 종목을 다시 훑는 중첩 루프를 고른다(10만 명에서 3.2초 → 고친 뒤 1.5초).
    // 작은 표라 몇 ms 면 끝난다. 읽기·쓰기를 막지 않는 잠금(SHARE UPDATE EXCLUSIVE)만 잡는다.
    jdbc.execute("analyze contest_listings");
    return ContestResponse.from(contest, listingRepository.findByIdContestId(contestId).size());
  }
}
