package com.jeffkm.mockleague.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP 로 사용자·대회·참가·주문을 만드는 테스트 도우미. 3주차③ 테스트부터 쓴다. */
public final class ApiFixture {

  private final MockMvc mvc;

  public ApiFixture(MockMvc mvc) {
    this.mvc = mvc;
  }

  public long createUser() throws Exception {
    String nickname = "u-" + UUID.randomUUID().toString().substring(0, 8);
    String res = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"" + nickname + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(res, "$.data.id")).longValue();
  }

  /** 2026-12-01 ~ endsOn 대회를 만들고 start 면 시작한다(시작하면 첫날 가격·공시가 생긴다). */
  public long createContest(String endsOn, long seed, boolean start) throws Exception {
    String res = mvc.perform(post("/api/admin/contests").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"테스트 리그","startsOn":"2026-12-01","endsOn":"%s","seed":%d}"""
                .formatted(endsOn, seed)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(res, "$.data.id")).longValue();
    if (start) {
      mvc.perform(post("/api/admin/contests/{id}/start", id)).andExpect(status().isOk());
    }
    return id;
  }

  public void join(long contestId, long userId) throws Exception {
    mvc.perform(post("/api/contests/{id}/participants", contestId).header("X-User-Id", userId))
        .andExpect(status().isCreated());
  }

  public ResultActions marketOrder(long contestId, long userId, String ticker, String side, long qty)
      throws Exception {
    return mvc.perform(post("/api/contests/{id}/orders", contestId)
        .header("X-User-Id", userId)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"ticker":"%s","side":"%s","quantity":%d}""".formatted(ticker, side, qty)));
  }

  public ResultActions limitOrder(long contestId, long userId, String ticker, String side, long qty,
      long limitPrice) throws Exception {
    return mvc.perform(post("/api/contests/{id}/orders", contestId)
        .header("X-User-Id", userId)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"ticker":"%s","side":"%s","quantity":%d,"type":"LIMIT","limitPrice":%d}"""
            .formatted(ticker, side, qty, limitPrice)));
  }

  public ResultActions attend(long contestId, long userId) throws Exception {
    return mvc.perform(post("/api/contests/{id}/attendance", contestId).header("X-User-Id", userId));
  }

  public ResultActions fetch(String url, Object... vars) throws Exception {
    return mvc.perform(get(url, vars));
  }

  public ResultActions getAs(long userId, String url, Object... vars) throws Exception {
    return mvc.perform(get(url, vars).header("X-User-Id", userId));
  }
}
