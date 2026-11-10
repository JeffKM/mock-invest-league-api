package com.jeffkm.mockleague.company;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

  /** 종목 코드로 찾기. 없을 수 있으므로 Optional. */
  Optional<Company> findByTicker(String ticker);

  /** 섹터별 목록(코드 순). */
  List<Company> findBySectorOrderByTickerAsc(Sector sector);
}
