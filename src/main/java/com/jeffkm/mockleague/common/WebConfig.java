package com.jeffkm.mockleague.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

  private final String adminToken;

  public WebConfig(@Value("${league.admin.token:}") String adminToken) {
    this.adminToken = adminToken;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    if (adminToken.isBlank()) {
      log.warn("league.admin.token 이 비어 있어 관리자 API 를 검사하지 않습니다(로컬 전용)");
      return;
    }
    registry.addInterceptor(new AdminTokenInterceptor(adminToken)).addPathPatterns("/api/admin/**");
  }
}
