package com.jeffkm.mockleague.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 조회 캐시(4주차②). 어떤 저장소(Redis, 서버 메모리, 없음)를 쓸지는 spring.cache.type 이 정하고, 코드는 모른다.
 *
 * <p>규칙: <b>키에 "바뀌지 않는 버전"을 넣는다.</b> 예: (대회, 날짜, 틱), 순위 스냅샷 id.
 * 그 버전의 데이터는 한 번 정해지면 바뀌지 않으므로 "캐시를 언제 지우나(무효화)"를 고민할 필요가 없다.
 * 새 틱·새 스냅샷이 되면 키가 달라져 자연히 새로 읽는다. 지난 키는 TTL·크기 제한으로 사라진다.
 *
 * <p>캐시가 고장 나도(예: Redis 가 죽음) 요청은 실패하지 않는다. 경고를 남기고 DB 에서 읽는다.
 * 캐시는 빠르게 하는 장치이지, 정답을 들고 있는 곳이 아니기 때문이다.
 */
@Component
public class ReadCache {

  public static final String LATEST_PRICES = "latestPrices";
  public static final String RANKING_TOP = "rankingTop";

  private static final Logger log = LoggerFactory.getLogger(ReadCache.class);

  private final CacheManager cacheManager;
  private final MeterRegistry meters;

  public ReadCache(CacheManager cacheManager, MeterRegistry meters) {
    this.cacheManager = cacheManager;
    this.meters = meters;
  }

  /**
   * 캐시에 있으면 꺼내고, 없으면 loader 로 읽어 cacheable 이 참일 때만 넣는다.
   * cacheable 이 거짓인 예: 아직 다 만들어지지 않은 데이터(넣으면 나중에 틀린 값이 남는다).
   */
  @SuppressWarnings("unchecked")
  public <T> T get(String cacheName, String key, Supplier<T> loader, Predicate<T> cacheable) {
    Cache cache = cacheManager.getCache(cacheName);
    if (cache != null) {
      try {
        Cache.ValueWrapper hit = cache.get(key);
        if (hit != null) {
          count(cacheName, "hit");
          return (T) hit.get();
        }
      } catch (RuntimeException e) {
        count(cacheName, "error");
        log.warn("캐시 읽기 실패, DB 로 대신 읽는다: cache={}, key={}, {}", cacheName, key, e.toString());
        return loader.get();
      }
    }
    count(cacheName, "miss");
    T value = loader.get();
    if (cache != null && cacheable.test(value)) {
      try {
        cache.put(key, value);
      } catch (RuntimeException e) {
        count(cacheName, "error");
        log.warn("캐시 쓰기 실패: cache={}, key={}, {}", cacheName, key, e.toString());
      }
    }
    return value;
  }

  private void count(String cacheName, String result) {
    meters.counter("league.cache", "cache", cacheName, "result", result).increment();
  }
}
