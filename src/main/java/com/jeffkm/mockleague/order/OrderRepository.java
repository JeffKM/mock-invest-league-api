package com.jeffkm.mockleague.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

  /** 멱등성(I6): 같은 계좌·같은 키로 이미 들어온 주문이 있는지. */
  Optional<Order> findByParticipantIdAndIdempotencyKey(Long participantId, String idempotencyKey);

  long countByParticipantId(Long participantId);

  Optional<Order> findByIdAndParticipantId(Long id, Long participantId);

  /**
   * 최근 주문. 정렬은 (created_at desc, id desc) 이고, 인덱스 (participant_id, created_at desc, id desc) 와 순서가 같다(V4).
   * 예전에는 "order by id desc" 였다. 그러면 플래너가 기본키를 뒤에서부터 읽으며 이 참가자 주문을 골라내는 길을 고를 수 있다.
   * 이 참가자의 주문이 오래전에 몰려 있으면 다른 사람 주문 10만 건을 건너뛴다(ROADMAP 18단계 실험 C).
   * 기본키로는 created_at 순서를 만들 수 없으니, 정렬을 바꾸면 그 길이 사라진다.
   */
  @Query("""
      select o from Order o join fetch o.company
       where o.participant.id = :participantId
       order by o.createdAt desc, o.id desc
      """)
  List<Order> findRecentWithCompany(@Param("participantId") Long participantId, Pageable pageable);

  /**
   * 정산 대상: 이 대회에서 day 까지(지난날에 밀린 것 포함) 아직 대기 중인 지정가 주문 id.
   * 엔티티가 아니라 id 만 가져오는 이유: 주문마다 계좌를 잠근 "뒤에" 새로 읽어야 하기 때문이다.
   */
  @Query("""
      select o.id from Order o
       where o.participant.contest.id = :contestId
         and o.status = com.jeffkm.mockleague.order.OrderStatus.PENDING
         and o.orderType = com.jeffkm.mockleague.order.OrderType.LIMIT
         and o.tradingDay <= :day
       order by o.id
      """)
  List<Long> findPendingLimitOrderIds(@Param("contestId") Long contestId, @Param("day") LocalDate day);

  @Query("select o.participant.id from Order o where o.id = :orderId")
  Optional<Long> findParticipantIdByOrderId(@Param("orderId") Long orderId);
}
