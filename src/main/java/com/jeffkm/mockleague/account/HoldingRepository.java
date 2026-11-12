package com.jeffkm.mockleague.account;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, HoldingId> {

  /** 계좌의 보유 종목 전체(내 계좌 화면용). */
  List<Holding> findByIdParticipantId(Long participantId);

  /**
   * 수량이 남은 보유와 회사 정보를 쿼리 한 번에 가져온다.
   * join fetch 가 없으면 보유마다 company 지연 로딩 SELECT 가 따로 나간다(N+1).
   */
  @Query("""
      select h from Holding h
      join fetch h.company
      where h.id.participantId = :participantId and h.quantity > 0
      order by h.company.ticker
      """)
  List<Holding> findOpenPositionsWithCompany(@Param("participantId") Long participantId);
}
