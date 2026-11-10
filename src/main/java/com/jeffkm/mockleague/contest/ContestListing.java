package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * 대회에 상장된 종목과 그 대회의 기준가.
 * 1주차에는 가격 엔진이 없으므로 이 basePrice 를 임시 현재가로 쓴다.
 */
@Entity
@Table(name = "contest_listings")
public class ContestListing {

  @EmbeddedId
  private ContestListingId id;

  @MapsId("contestId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "contest_id")
  private Contest contest;

  @MapsId("companyId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(nullable = false)
  private long basePrice;

  protected ContestListing() {}

  /** 회사의 기본 기준가로 대회에 상장한다. */
  public static ContestListing of(Contest contest, Company company) {
    ContestListing listing = new ContestListing();
    listing.id = new ContestListingId(contest.getId(), company.getId());
    listing.contest = contest;
    listing.company = company;
    listing.basePrice = company.getBasePrice();
    return listing;
  }

  public ContestListingId getId() {
    return id;
  }

  public Contest getContest() {
    return contest;
  }

  public Company getCompany() {
    return company;
  }

  public long getBasePrice() {
    return basePrice;
  }
}
