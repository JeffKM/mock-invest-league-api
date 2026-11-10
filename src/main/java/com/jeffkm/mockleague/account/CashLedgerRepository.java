package com.jeffkm.mockleague.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashLedgerRepository extends JpaRepository<CashLedger, Long> {

  /** 계좌의 원장 합계. I5 검증용. 기록이 없으면 0. */
  @Query("select coalesce(sum(l.amount), 0) from CashLedger l where l.participant.id = :participantId")
  long sumAmountByParticipantId(@Param("participantId") Long participantId);
}
