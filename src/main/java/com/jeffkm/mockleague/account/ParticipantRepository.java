package com.jeffkm.mockleague.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

  /** 이 사용자의 이 대회 계좌. ContestId 는 contest.id 경로로 해석된다(조인 없이 FK 컬럼 비교). */
  Optional<Participant> findByContestIdAndUserId(Long contestId, Long userId);
}
