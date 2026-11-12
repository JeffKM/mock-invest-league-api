package com.jeffkm.mockleague.common;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 는 @EnableCaching 이 있어야 spring.cache.type 에 맞는 CacheManager 를 만든다.
 * 캐시는 애노테이션(@Cacheable) 대신 ReadCache 로 직접 다룬다: "다 만들어진 데이터만 넣기"와
 * "캐시가 고장 나도 DB 로 읽기"를 한 곳에서 분명하게 하기 위해서다.
 */
@Configuration
@EnableCaching
public class CacheConfig {}
