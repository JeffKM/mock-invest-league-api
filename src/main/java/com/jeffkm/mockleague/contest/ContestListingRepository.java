package com.jeffkm.mockleague.contest;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContestListingRepository extends JpaRepository<ContestListing, ContestListingId> {

  /** 대회의 상장 종목 전체. 복합키 안의 필드는 id_ 경로로 찾는다. */
  List<ContestListing> findByIdContestId(Long contestId);

  /** 가격 생성용: 상장 종목과 회사(티커·변동성)를 한 번에. join fetch 가 없으면 종목마다 SELECT 가 따로 나간다. */
  @Query("select l from ContestListing l join fetch l.company where l.id.contestId = :contestId")
  List<ContestListing> findWithCompanyByContestId(@Param("contestId") Long contestId);
}
