package com.jeffkm.mockleague.common;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
  /** 앞사람의 읽기를 이만큼까지만 기다린다. 넘으면 직접 읽는다(앞사람이 멈춰도 같이 멈추지 않게). */
  private static final long FOLLOWER_WAIT_MS = 2_000;

  private final CacheManager cacheManager;
  private final MeterRegistry meters;
  private final boolean singleFlight;
  /** single-flight: 지금 DB 에서 읽고 있는 키 → 그 결과. 같은 키로 놓친 뒤 사람은 새로 읽지 않고 이것을 기다린다. */
  private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
  /** 지금 DB 에서 읽는 중인 키와 그 수(6주차⑤ 측정). 같은 키를 동시에 여럿이 읽으면 스탬피드다. */
  private final Map<String, Integer> loading = new ConcurrentHashMap<>();

  public ReadCache(CacheManager cacheManager, MeterRegistry meters) {
    this(cacheManager, meters, false);
  }

  /**
   * league.cache.single-flight 가 참이면 같은 키를 동시에 놓친 요청 중 한 명만 DB 를 읽는다(6주차⑤, 기본: 꺼짐).
   * 끄고 켜며 같은 이미지로 전후를 재기 위해 설정으로 두었다.
   */
  @Autowired
  public ReadCache(CacheManager cacheManager, MeterRegistry meters,
      @Value("${league.cache.single-flight:false}") boolean singleFlight) {
    this.cacheManager = cacheManager;
    this.meters = meters;
    this.singleFlight = singleFlight;
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
    if (!singleFlight) {
      return loadAndPut(cache, cacheName, key, loader, cacheable);
    }
    String id = cacheName + "|" + key;
    CompletableFuture<Object> mine = new CompletableFuture<>();
    CompletableFuture<Object> leader = inFlight.putIfAbsent(id, mine);
    if (leader != null) {
      T shared = (T) awaitLeader(leader, cacheName, key);
      if (shared != null) {
        return shared;
      }
      return loadAndPut(cache, cacheName, key, loader, cacheable); // 앞사람이 실패했거나 너무 늦다
    }
    try {
      Cache.ValueWrapper filled = cache == null ? null : cache.get(key); // 방금 끝난 앞사람이 넣고 나갔을 수 있다
      T value = filled != null ? (T) filled.get() : loadAndPut(cache, cacheName, key, loader, cacheable);
      mine.complete(value);
      return value;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(id, mine);
    }
  }

  /** 앞사람의 결과를 기다린다. 받으면 그 값, 실패·시간 초과면 null(그때는 직접 읽는다). */
  private Object awaitLeader(CompletableFuture<Object> leader, String cacheName, String key) {
    try {
      Object value = leader.get(FOLLOWER_WAIT_MS, TimeUnit.MILLISECONDS);
      count(cacheName, "coalesced");
      return value;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException e) {
      log.warn("앞선 캐시 읽기를 기다리지 못해 직접 읽는다: cache={}, key={}, {}", cacheName, key, e.toString());
      return null;
    }
  }

  private <T> T loadAndPut(Cache cache, String cacheName, String key, Supplier<T> loader, Predicate<T> cacheable) {
    count(cacheName, "miss");
    T value = load(cacheName, key, loader);
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

  /**
   * loader 를 부르며 두 가지를 잰다. league.cache.load: 놓친 한 번이 DB 에서 얼마나 걸리나.
   * league.cache.concurrent_loads: 읽기를 시작할 때 같은 키를 이미 몇 명이 읽고 있었나(자기 포함). 최댓값이 1 이면 스탬피드가 없다.
   */
  private <T> T load(String cacheName, String key, Supplier<T> loader) {
    String id = cacheName + "|" + key;
    int concurrent = loading.merge(id, 1, Integer::sum);
    DistributionSummary.builder("league.cache.concurrent_loads").tag("cache", cacheName).register(meters)
        .record(concurrent);
    Timer.Sample sample = Timer.start(meters);
    try {
      return loader.get();
    } finally {
      sample.stop(Timer.builder("league.cache.load").tag("cache", cacheName).register(meters));
      loading.computeIfPresent(id, (k, n) -> n == 1 ? null : n - 1);
    }
  }

  private void count(String cacheName, String result) {
    meters.counter("league.cache", "cache", cacheName, "result", result).increment();
  }
}
