package com.jeffkm.leaguepush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 시세 푸시 서버(7주차⑤). 본 API 가 틱마다 Redis 채널로 보낸 시세·순위를 SSE 로 연결된 클라이언트에게 방송한다.
 * 연결을 오래 쥐는 일만 하므로 Netty 로 띄운다: 같은 SSE 연결이 Tomcat 에서 힙 약 100KB, Netty 에서 약 13KB 였다(ROADMAP 43단계).
 */
@SpringBootApplication
public class PushApplication {

  public static void main(String[] args) {
    SpringApplication.run(PushApplication.class, args);
  }
}
