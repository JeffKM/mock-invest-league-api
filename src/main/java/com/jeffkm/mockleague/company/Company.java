package com.jeffkm.mockleague.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 가상 상장사. 시드(V2__seed_companies.sql)로만 들어오고 바뀌지 않으므로 setter가 없다.
 */
@Entity
@Table(name = "companies")
public class Company {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 8)
  private String ticker;

  @Column(nullable = false, unique = true, length = 50)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Sector sector;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Volatility volatility;

  @Column(nullable = false)
  private long basePrice;

  @Column(length = 200)
  private String description;

  /** JPA가 조회 결과로 객체를 만들 때 쓰는 생성자. 코드에서 직접 부르지 않는다. */
  protected Company() {}

  public Long getId() {
    return id;
  }

  public String getTicker() {
    return ticker;
  }

  public String getName() {
    return name;
  }

  public Sector getSector() {
    return sector;
  }

  public Volatility getVolatility() {
    return volatility;
  }

  public long getBasePrice() {
    return basePrice;
  }

  public String getDescription() {
    return description;
  }
}
