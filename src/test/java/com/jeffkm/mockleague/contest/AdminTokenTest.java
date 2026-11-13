package com.jeffkm.mockleague.contest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jeffkm.mockleague.common.AdminTokenInterceptor;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 5주차② 배포: 토큰을 설정하면 /api/admin/** 는 X-Admin-Token 이 맞아야만 통과한다. */
@SpringBootTest(properties = "league.admin.token=test-admin-token")
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, TestClockConfig.class})
class AdminTokenTest {

  @Autowired MockMvc mvc;

  private static MockHttpServletRequestBuilder createContest() {
    return post("/api/admin/contests").contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"name":"토큰 리그","startsOn":"2026-12-01","endsOn":"2026-12-31"}""");
  }

  @Test
  void 토큰이_없으면_401() throws Exception {
    mvc.perform(createContest())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("ADMIN_UNAUTHORIZED"));
  }

  @Test
  void 토큰이_틀리면_401() throws Exception {
    mvc.perform(createContest().header(AdminTokenInterceptor.HEADER, "test-admin-tokeN"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 토큰이_맞으면_통과() throws Exception {
    mvc.perform(createContest().header(AdminTokenInterceptor.HEADER, "test-admin-token"))
        .andExpect(status().isCreated());
  }

  @Test
  void 관리자_경로가_아니면_토큰을_보지_않는다() throws Exception {
    mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"tok-" + System.nanoTime() % 1_000_000 + "\"}"))
        .andExpect(status().isCreated());
  }
}
