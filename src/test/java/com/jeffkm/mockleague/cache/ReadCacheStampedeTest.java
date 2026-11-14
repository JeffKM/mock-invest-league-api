package com.jeffkm.mockleague.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jeffkm.mockleague.common.ReadCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * 캐시 스탬피드(6주차⑤): 키가 바뀐 직후 여러 요청이 동시에 놓치면 모두가 DB 를 읽는가.
 * DB 대신 "다들 들어올 때까지 기다리는 loader"를 써서 동시에 놓친 상황을 확실히 만든다.
 */
class ReadCacheStampedeTest {

  private static final int REQUESTS = 8;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ReadCache cache = new ReadCache(new ConcurrentMapCacheManager(ReadCache.RANKING_TOP), meters);
  private final ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void 같은_키를_동시에_놓치면_모두가_DB_를_읽고_그_폭을_지표로_남긴다() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch allIn = new CountDownLatch(REQUESTS);
    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < REQUESTS; i++) {
      results.add(pool.submit(() -> cache.get(ReadCache.RANKING_TOP, "snap-7:20", () -> {
        loads.incrementAndGet();
        allIn.countDown();
        await(allIn); // 모두가 loader 안에 들어올 때까지 붙잡는다 = 같은 순간에 놓친 상태
        return "top20";
      }, v -> true)));
    }
    for (Future<String> r : results) {
      assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("top20");
    }

    assertThat(loads.get()).isEqualTo(REQUESTS); // 지금은 놓친 수만큼 DB 를 읽는다
    var concurrent = meters.find("league.cache.concurrent_loads").tag("cache", ReadCache.RANKING_TOP).summary();
    assertThat(concurrent.max()).isEqualTo(REQUESTS);
    assertThat(meters.find("league.cache.load").tag("cache", ReadCache.RANKING_TOP).timer().count())
        .isEqualTo(REQUESTS);
  }

  @Test
  void 차례로_놓치면_동시_로드는_1이다() {
    cache.get(ReadCache.RANKING_TOP, "a", () -> "x", v -> true);
    cache.get(ReadCache.RANKING_TOP, "b", () -> "y", v -> true);
    var concurrent = meters.find("league.cache.concurrent_loads").tag("cache", ReadCache.RANKING_TOP).summary();
    assertThat(concurrent.max()).isEqualTo(1);
  }

  @Test
  void single_flight_를_켜면_한_명만_읽고_나머지는_그_결과를_받는다() throws Exception {
    ReadCache flight = new ReadCache(new ConcurrentMapCacheManager(ReadCache.RANKING_TOP), meters, true);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < REQUESTS; i++) {
      results.add(pool.submit(() -> flight.get(ReadCache.RANKING_TOP, "snap-8:20", () -> {
        loads.incrementAndGet();
        await(release); // 첫 사람이 DB 를 읽는 동안 나머지가 도착한다
        return "top20";
      }, v -> true)));
    }
    Thread.sleep(500);
    release.countDown();
    for (Future<String> r : results) {
      assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("top20");
    }

    assertThat(loads.get()).isEqualTo(1);
    assertThat(meters.find("league.cache").tag("result", "coalesced").counter().count()).isEqualTo(REQUESTS - 1);
  }

  @Test
  void single_flight_에서_앞사람이_실패하면_기다리던_사람은_직접_읽는다() throws Exception {
    ReadCache flight = new ReadCache(new ConcurrentMapCacheManager(ReadCache.RANKING_TOP), meters, true);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    Future<String> first = pool.submit(() -> flight.get(ReadCache.RANKING_TOP, "k", () -> {
      loads.incrementAndGet();
      await(release);
      throw new IllegalStateException("DB 오류");
    }, v -> true));
    Thread.sleep(200);
    Future<String> second = pool.submit(() -> flight.get(ReadCache.RANKING_TOP, "k", () -> {
      loads.incrementAndGet();
      return "ok";
    }, v -> true));
    Thread.sleep(200);
    release.countDown();

    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
    assertThat(loads.get()).isEqualTo(2);
    assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
