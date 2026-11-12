package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.company.Volatility;
import java.time.LocalDate;
import java.util.Random;

/**
 * 하루치 틱 가격 경로를 만든다(F4). DB·Spring 을 모르는 순수 계산이라 단위 테스트로 수천 번 돌려 볼 수 있다.
 *
 * <p>모델: 기하 브라운 운동(GBM). 틱마다 "직전 가격 × e^(작은 무작위 수익률)"로 움직인다.
 * 곱으로 움직이므로 가격이 0 아래로 내려가지 않고, 비싼 종목과 싼 종목이 같은 "비율"로 흔들린다.
 *
 * <p>규칙:
 * <ul>
 *   <li>같은 (대회 시드, 티커, 날짜, 전일 종가) → 언제 어디서 돌려도 같은 경로. 재실행·서버 여러 대에서도 결과가 같다.</li>
 *   <li>0번 틱(시가) = 전일 종가. 1번 틱부터 움직인다(1차 범위 단순화: 시가 갭 없음).</li>
 *   <li>모든 틱은 전일 종가 ±제한폭 안(I8). 벽에 닿으면 그 가격에 붙어 있다가 다시 움직인다.</li>
 * </ul>
 */
public final class PriceEngine {

  private static final long BP = 10_000;

  private PriceEngine() {}

  /**
   * @param previousClose 전 거래일 종가(첫날은 상장 기준가)
   * @param ticks 하루 틱 수(기본 78)
   * @param priceLimitBp 가격 제한폭(bp). 3000 = ±30%
   * @return 틱 번호 순서의 가격 배열(길이 = ticks)
   */
  public static long[] generateDay(long contestSeed, String ticker, LocalDate day, long previousClose,
      Volatility volatility, int ticks, int priceLimitBp) {
    if (previousClose <= 0 || ticks <= 0 || priceLimitBp < 0 || priceLimitBp > BP) {
      throw new IllegalArgumentException("가격 생성 입력이 올바르지 않습니다.");
    }
    // 제한폭 경계를 정수로: 위는 버림, 아래는 올림 → 경계값 자체도 ±30% 를 넘지 않는다. 가격은 최소 1원
    long upper = Math.multiplyExact(previousClose, BP + priceLimitBp) / BP;
    long lower = Math.max(1, ceilDiv(Math.multiplyExact(previousClose, BP - priceLimitBp), BP));

    // 하루 표준편차를 틱 수로 나눠 준다: 분산은 시간에 비례하므로 틱 하나의 σ = 하루 σ / √틱 수
    double sigma = volatility.dailySigma() / Math.sqrt(ticks);
    // e^(σZ) 의 평균은 1 보다 조금 크다(e^(σ²/2)). −σ²/2 를 빼 줘야 "평균적으로 오르지도 내리지도 않는" 경로가 된다
    double drift = -sigma * sigma / 2;

    Random random = new Random(seedFor(contestSeed, ticker, day));
    long[] prices = new long[ticks];
    prices[0] = previousClose;
    double x = previousClose;
    for (int i = 1; i < ticks; i++) {
      x = x * Math.exp(drift + sigma * random.nextGaussian());
      long rounded = Math.round(x);
      long clamped = Math.min(upper, Math.max(lower, rounded));
      if (clamped != rounded) {
        x = clamped; // 벽 밖으로 나간 값은 버리고 벽에서 다시 출발
      }
      prices[i] = clamped;
    }
    return prices;
  }

  /**
   * 대회 시드·티커·날짜를 섞어 종목·날짜마다 다른 난수열을 만든다.
   * 회사 id(DB 가 정하는 값) 대신 티커를 쓴다: DB 를 새로 만들어 id 가 바뀌어도 같은 경로가 나와야 한다.
   * java.util.Random 을 쓰는 이유: 알고리즘이 Java 명세에 고정돼 있어 JDK 버전이 바뀌어도 같은 수가 나온다.
   */
  static long seedFor(long contestSeed, String ticker, LocalDate day) {
    long h = contestSeed;
    h = h * 31 + ticker.hashCode();   // String.hashCode 도 명세에 고정된 계산이다
    h = h * 31 + day.toEpochDay();
    // splitmix64 마무리 섞기: 비슷한 입력(하루 차이)이 비슷한 시드가 되지 않게 비트를 흩는다
    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
    return h ^ (h >>> 31);
  }

  private static long ceilDiv(long a, long b) {
    return -Math.floorDiv(-a, b);
  }
}
