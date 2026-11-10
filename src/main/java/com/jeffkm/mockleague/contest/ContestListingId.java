package com.jeffkm.mockleague.contest;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** contest_listings 의 복합 기본키 (contest_id, company_id). */
@Embeddable
public class ContestListingId implements Serializable {

  @Column(name = "contest_id")
  private Long contestId;

  @Column(name = "company_id")
  private Long companyId;

  protected ContestListingId() {}

  public ContestListingId(Long contestId, Long companyId) {
    this.contestId = contestId;
    this.companyId = companyId;
  }

  public Long getContestId() {
    return contestId;
  }

  public Long getCompanyId() {
    return companyId;
  }

  // 복합키는 값으로 비교돼야 영속성 컨텍스트에서 같은 키를 같은 엔티티로 찾는다
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ContestListingId other)) {
      return false;
    }
    return Objects.equals(contestId, other.contestId) && Objects.equals(companyId, other.companyId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(contestId, companyId);
  }
}
