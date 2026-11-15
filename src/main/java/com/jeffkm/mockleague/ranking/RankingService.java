package com.jeffkm.mockleague.ranking;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.common.ReadCache;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.ranking.dto.MyRankingResponse;
import com.jeffkm.mockleague.ranking.dto.RankingResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 순위(F12). 쓰기는 배치(스냅샷 찍기), 읽기는 API(스냅샷 조회)로 나뉜다.
 * 조회 때마다 1,000명의 총자산을 새로 계산하지 않는다: 순위 조회는 장중 내내 몰리지만 순위는 틱(5분)마다만 바뀐다.
 */
@Service
public class RankingService {

  private static final Logger log = LoggerFactory.getLogger(RankingService.class);
  private static final long BP = 10_000;

  private final ContestRepository contestRepository;
  private final RankingRepository rankingRepository;
  private final ReadCache readCache;

  public RankingService(ContestRepository contestRepository, RankingRepository rankingRepository,
      ReadCache readCache) {
    this.contestRepository = contestRepository;
    this.rankingRepository = rankingRepository;
    this.readCache = readCache;
  }

  /**
   * at 시점 스냅샷을 찍는다. 이미 있으면 아무것도 하지 않는다(멱등).
   * 머리 행과 줄들을 한 트랜잭션에 넣는다: 머리만 있고 줄이 없는 "빈 순위표"가 보이지 않게.
   *
   * @return 새로 찍었으면 true
   */
  @Transactional
  public boolean takeSnapshot(Long contestId, TickPosition at) {
    Optional<Long> snapshotId = rankingRepository.createSnapshot(contestId, at);
    if (snapshotId.isEmpty()) {
      return false;
    }
    int rows = rankingRepository.fillEntries(snapshotId.get(), contestId, at);
    log.info("순위 스냅샷: contest={}, {} {}틱, {}명", contestId, at.day(), at.tick(), rows);
    return true;
  }

  /**
   * 지난 날의 장중 스냅샷을 지우고 날마다 마지막 스냅샷만 남긴다(일일 배치에서 부른다).
   * 5분마다 1,000줄이면 대회 하나에 하루 78,000줄, 대회 10개면 78만 줄이 쌓이기 때문이다.
   * 오늘 스냅샷과 가장 최근 스냅샷은 지우지 않는다 → 순위 조회에 영향이 없다. 몇 번 돌려도 같다.
   */
  @Transactional
  public int pruneIntraday(Long contestId, LocalDate today) {
    int pruned = rankingRepository.pruneIntradayBefore(contestId, today);
    if (pruned > 0) {
      log.info("순위 스냅샷 정리: contest={}, {}장 삭제", contestId, pruned);
    }
    return pruned;
  }

  @Transactional(readOnly = true)
  public RankingResponse top(Long contestId, int limit) {
    Contest contest = findContest(contestId);
    return rankingRepository.latestSnapshot(contestId)
        .map(s -> new RankingResponse(s.tradingDay(), s.tickNo(), contest.tickStartAt(s.tradingDay(), s.tickNo()),
            s.participants(), topEntries(s.id(), limit).stream().map(e -> toDto(contest, e)).toList()))
        .orElse(new RankingResponse(null, null, null, 0, List.of()));
  }

  @Transactional(readOnly = true)
  public MyRankingResponse mine(Long contestId, Long userId) {
    Contest contest = findContest(contestId);
    if (!rankingRepository.isParticipant(contestId, userId)) {
      throw new BusinessException(ErrorCode.NOT_PARTICIPANT);
    }
    return rankingRepository.latestSnapshot(contestId)
        .map(s -> new MyRankingResponse(s.tradingDay(), s.tickNo(), contest.tickStartAt(s.tradingDay(), s.tickNo()),
            s.participants(),
            rankingRepository.entryOfUser(s.id(), userId).map(e -> toDto(contest, e)).orElse(null)))
        .orElse(new MyRankingResponse(null, null, null, 0, null));
  }

  /**
   * 스냅샷의 상위 limit 명. 캐시 키는 (스냅샷 id, limit). 스냅샷은 머리와 줄이 한 트랜잭션으로 커밋되므로
   * 보이는 순간 이미 완성돼 있고, 그 뒤로 바뀌지 않는다 → 무효화가 필요 없다.
   * "가장 최근 스냅샷이 무엇인지"는 캐시하지 않는다(새 스냅샷이 생기면 바로 바뀌어야 하므로). 그 쿼리는 인덱스 한 번이다.
   */
  private List<RankingRepository.Entry> topEntries(Long snapshotId, int limit) {
    return readCache.get(ReadCache.RANKING_TOP, snapshotId + ":" + limit,
        () -> new ArrayList<>(rankingRepository.top(snapshotId, limit)), entries -> true);
  }

  private Contest findContest(Long contestId) {
    return contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
  }

  /** 수익 = 총자산 − 초기 자금. 출석 보너스도 수익에 들어간다(꾸준히 들어온 사람에게 주는 보상). */
  private static RankingResponse.Entry toDto(Contest contest, RankingRepository.Entry e) {
    long profit = e.totalAssets() - contest.getInitialCash();
    long returnBp = Math.floorDiv(Math.multiplyExact(profit, BP), contest.getInitialCash());
    return new RankingResponse.Entry(e.rank(), e.nickname(), e.totalAssets(), profit, returnBp);
  }
}
