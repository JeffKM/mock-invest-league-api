package com.jeffkm.mockleague.contest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestRepository extends JpaRepository<Contest, Long> {

  /** 일일 배치용: 상태별 대회 목록. */
  List<Contest> findByStatus(ContestStatus status);
}
