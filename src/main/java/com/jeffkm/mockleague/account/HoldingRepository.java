package com.jeffkm.mockleague.account;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingRepository extends JpaRepository<Holding, HoldingId> {

  /** 계좌의 보유 종목 전체(내 계좌 화면용). */
  List<Holding> findByIdParticipantId(Long participantId);
}
