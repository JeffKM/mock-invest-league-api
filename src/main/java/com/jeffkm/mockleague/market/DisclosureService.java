package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.market.dto.DisclosureResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공시 조회(F6). "지금" 볼 수 있는 공시만 돌려준다(I11). */
@Service
public class DisclosureService {

  private final ContestRepository contestRepository;
  private final DisclosureRepository disclosureRepository;
  private final Clock clock;

  public DisclosureService(ContestRepository contestRepository, DisclosureRepository disclosureRepository,
      Clock clock) {
    this.contestRepository = contestRepository;
    this.disclosureRepository = disclosureRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<DisclosureResponse> visible(Long contestId, int limit) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    // 가격과 같은 "지금 볼 수 있는 마지막 틱"을 쓴다. 대회 시작 전이면 볼 공시가 없다
    return contest.latestVisibleTick(LocalDateTime.now(clock))
        .map(at -> disclosureRepository.visibleUpTo(contestId, at, limit).stream()
            .map(r -> new DisclosureResponse(r.id(), r.tradingDay(), r.tickNo(),
                contest.tickStartAt(r.tradingDay(), r.tickNo()), scopeOf(r),
                r.ticker(), r.companyName(), r.sector(), r.title()))
            .toList())
        .orElse(List.of());
  }

  private static DisclosureScope scopeOf(DisclosureRepository.Row r) {
    if (r.companyId() != null) {
      return DisclosureScope.COMPANY;
    }
    return r.sector() != null ? DisclosureScope.SECTOR : DisclosureScope.MARKET;
  }
}
