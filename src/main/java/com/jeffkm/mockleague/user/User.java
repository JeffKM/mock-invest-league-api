package com.jeffkm.mockleague.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/** 사용자. 1주차 인증은 X-User-Id 헤더로 임시 처리한다. */
@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 30)
  private String nickname;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  protected User() {}

  public User(String nickname) {
    this.nickname = nickname;
  }

  public Long getId() {
    return id;
  }

  public String getNickname() {
    return nickname;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
