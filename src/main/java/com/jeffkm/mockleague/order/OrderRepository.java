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

  @Query("select o from Order o join fetch o.company where o.participant.id = :participantId order by o.id desc")
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
