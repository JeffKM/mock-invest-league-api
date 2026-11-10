package com.jeffkm.mockleague.account;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** holdings 의 복합 기본키 (participant_id, company_id). */
@Embeddable
public class HoldingId implements Serializable {

  @Column(name = "participant_id")
  private Long participantId;

  @Column(name = "company_id")
  private Long companyId;

  protected HoldingId() {}

  public HoldingId(Long participantId, Long companyId) {
    this.participantId = participantId;
    this.companyId = companyId;
  }

  public Long getParticipantId() {
    return participantId;
  }

  public Long getCompanyId() {
    return companyId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HoldingId other)) {
      return false;
    }
    return Objects.equals(participantId, other.participantId)
        && Objects.equals(companyId, other.companyId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(participantId, companyId);
  }
}
