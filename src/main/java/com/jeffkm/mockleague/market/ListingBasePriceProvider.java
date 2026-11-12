package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.contest.ContestListing;
import com.jeffkm.mockleague.contest.ContestListingId;
import com.jeffkm.mockleague.contest.ContestListingRepository;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 1주차 임시 현재가: 가격이 움직이지 않고 상장 기준가 그대로다. */
@Component
public class ListingBasePriceProvider implements PriceProvider {

  private final ContestListingRepository listingRepository;

  public ListingBasePriceProvider(ContestListingRepository listingRepository) {
    this.listingRepository = listingRepository;
  }

  @Override
  public long currentPrice(Long contestId, Long companyId) {
    return listingRepository.findById(new ContestListingId(contestId, companyId))
        .map(ContestListing::getBasePrice)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 대회에 상장되지 않은 종목입니다."));
  }

  @Override
  public Map<Long, Long> currentPrices(Long contestId) {
    return listingRepository.findByIdContestId(contestId).stream()
        .collect(Collectors.toMap(l -> l.getId().getCompanyId(), ContestListing::getBasePrice));
  }
}
