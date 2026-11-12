package com.jeffkm.mockleague.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

  /** 이 사용자의 이 대회 계좌. ContestId 는 contest.id 경로로 해석된다(조인 없이 FK 컬럼 비교). */
  Optional<Participant> findByContestIdAndUserId(Long contestId, Long userId);

  /**
   * 비관적 락: 계좌 행을 읽으면서 잠근다(PostgreSQL 에서는 SELECT ... FOR NO KEY UPDATE).
   * 같은 계좌를 잠그려는 다른 트랜잭션은 이 트랜잭션이 커밋·롤백할 때까지 기다린다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Participant p where p.contest.id = :contestId and p.user.id = :userId")
  Optional<Participant> findForUpdate(@Param("contestId") Long contestId, @Param("userId") Long userId);

  /**
   * 조건부 UPDATE: 잔고가 충분할 때만 뺀다. 읽기와 쓰기가 한 문장이라 사이에 끼어들 틈이 없다.
   * 반환값은 바뀐 행 수. 0 이면 잔고 부족이다.
   */
  @Modifying
  @Query("update Participant p set p.cash = p.cash - :amount where p.id = :id and p.cash >= :amount")
  int withdrawIfEnough(@Param("id") Long id, @Param("amount") long amount);

  /** 조건부 UPDATE 방식의 입금. 더하기는 조건이 필요 없다. */
  @Modifying
  @Query("update Participant p set p.cash = p.cash + :amount where p.id = :id")
  int depositAtomically(@Param("id") Long id, @Param("amount") long amount);
}
