package com.jeffkm.mockleague.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.TickPosition;
import com.jeffkm.mockleague.market.PriceProvider;
import com.jeffkm.mockleague.ranking.RankingService;
import com.jeffkm.mockleague.ranking.dto.RankingResponse;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 순위 스냅샷을 찍은 직후(틱이 바뀐 직후) 그 대회의 시세와 상위 순위를 한 메시지로 만들어 푸시 서버로 넘긴다.
 * 클라이언트가 바뀌지 않은 값을 계속 조회하는 대신, 바뀔 때 한 번 받게 한다(ROADMAP 43·44단계).
 * 실패해도 던지지 않는다: 스냅샷은 이미 끝났고, 푸시는 다음 틱에 다시 간다.
 */
@Service
public class TickBroadcaster {

  static final int TOP = 20;
  private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

  private final RankingService rankingService;
  private final PriceProvider priceProvider;
  private final CompanyRepository companyRepository;
  private final TickPublisher publisher;
  private final ObjectMapper json;

  public TickBroadcaster(RankingService rankingService, PriceProvider priceProvider,
      CompanyRepository companyRepository, TickPublisher publisher, ObjectMapper json) {
    this.rankingService = rankingService;
    this.priceProvider = priceProvider;
    this.companyRepository = companyRepository;
    this.publisher = publisher;
    this.json = json;
  }

  /** 메시지: {contestId, tradingDay, tick, prices: {종목코드: 가격}, ranking: 순위표(상위 20)} */
  public record Message(long contestId, String tradingDay, int tick, Map<String, Long> prices, RankingResponse ranking) {}

  public void broadcast(long contestId, TickPosition at) {
    try {
      Map<Long, Long> byId = priceProvider.currentPrices(contestId);
      Map<String, Long> prices = new TreeMap<>();
      for (Company company : companyRepository.findAllById(byId.keySet())) {
        prices.put(company.getTicker(), byId.get(company.getId()));
      }
      Message message = new Message(contestId, at.day().toString(), at.tick(), prices, rankingService.top(contestId, TOP));
      publisher.publish(contestId, json.writeValueAsString(message));
    } catch (JsonProcessingException | RuntimeException e) {
      log.warn("푸시 메시지 만들기 실패: contest={} ({})", contestId, e.getClass().getSimpleName());
    }
  }
}
