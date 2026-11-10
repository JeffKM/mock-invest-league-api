package com.jeffkm.mockleague.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

  /** 멱등성(I6): 같은 계좌·같은 키로 이미 들어온 주문이 있는지. */
  Optional<Order> findByParticipantIdAndIdempotencyKey(Long participantId, String idempotencyKey);
}
