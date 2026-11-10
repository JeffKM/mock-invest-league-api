package com.jeffkm.mockleague.contest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContestListingRepository extends JpaRepository<ContestListing, ContestListingId> {

  /** 대회의 상장 종목 전체. 복합키 안의 필드는 id_ 경로로 찾는다. */
  List<ContestListing> findByIdContestId(Long contestId);
}
