package com.jeffkm.mockleague.account;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

  // ---------- 조건부 UPDATE 방식(LockStrategy.CONDITIONAL) 전용 ----------

  /**
   * 보유 행이 없으면 빈 행을 만든다. 이미 있으면(다른 요청이 먼저 만들었어도) 아무것도 하지 않는다.
   * 동시에 두 요청이 INSERT 해도 PK 충돌 오류가 나지 않는다(PostgreSQL ON CONFLICT).
   */
  @Modifying
  @Query(value = """
      insert into holdings (participant_id, company_id, quantity, reserved_quantity, total_cost, version)
      values (:participantId, :companyId, 0, 0, 0, 0)
      on conflict (participant_id, company_id) do nothing
      """, nativeQuery = true)
  int insertEmptyIfAbsent(@Param("participantId") Long participantId, @Param("companyId") Long companyId);

  @Modifying
  @Query(value = """
      update holdings
         set quantity = quantity + :qty, total_cost = total_cost + :cost
       where participant_id = :participantId and company_id = :companyId
      """, nativeQuery = true)
  int increaseAtomically(@Param("participantId") Long participantId, @Param("companyId") Long companyId,
      @Param("qty") long qty, @Param("cost") long cost);

  /**
   * 팔 수 있는 수량이 충분할 때만 줄인다. 매입 원가는 Holding.decrease 와 같은 규칙으로 덜어 낸다
   * (전부 팔면 전부, 일부면 비율대로 버림). SET 의 오른쪽은 모두 "바꾸기 전 값"을 본다.
   * 단점: 같은 규칙이 Java(Holding.decrease)와 SQL 두 곳에 생긴다.
   */
  @Modifying
  @Query(value = """
      update holdings
         set total_cost = total_cost - case when quantity = :qty then total_cost
                                            else total_cost * :qty / quantity end,
             quantity = quantity - :qty
       where participant_id = :participantId and company_id = :companyId
         and quantity - reserved_quantity >= :qty
      """, nativeQuery = true)
  int decreaseIfAvailable(@Param("participantId") Long participantId, @Param("companyId") Long companyId,
      @Param("qty") long qty);
}
