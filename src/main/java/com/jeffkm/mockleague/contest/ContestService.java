package com.jeffkm.mockleague.contest;

import com.jeffkm.mockleague.common.BusinessException;
import com.jeffkm.mockleague.common.ErrorCode;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.dto.ContestResponse;
import com.jeffkm.mockleague.contest.dto.CreateContestRequest;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대회 관리(관리자용). 1주차에는 인증 없이 열어 두고, 상태 전환도 배치 대신 수동 API로 한다(F2는 3주차).
 */
@Service
public class ContestService {

  private final ContestRepository contestRepository;
  private final ContestListingRepository listingRepository;
  private final CompanyRepository companyRepository;

  public ContestService(ContestRepository contestRepository, ContestListingRepository listingRepository,
      CompanyRepository companyRepository) {
    this.contestRepository = contestRepository;
    this.listingRepository = listingRepository;
    this.companyRepository = companyRepository;
  }

  /** 기본 규칙으로 대회를 만들고 상장사 전체를 상장한다. 대회와 상장 목록은 한 트랜잭션: 반쪽짜리 대회가 남지 않는다. */
  @Transactional
  public ContestResponse create(CreateContestRequest request) {
    if (request.startsOn().isAfter(request.endsOn())) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작일이 종료일보다 늦을 수 없습니다.");
    }
    long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong();
    Contest contest = contestRepository.save(
        Contest.withDefaults(request.name(), request.startsOn(), request.endsOn(), seed));
    List<ContestListing> listings = companyRepository.findAll().stream()
        .map(company -> ContestListing.of(contest, company))
        .toList();
    listingRepository.saveAll(listings);
    return ContestResponse.from(contest, listings.size());
  }

  /** SCHEDULED → RUNNING. 이미 시작했거나 끝난 대회면 409. */
  @Transactional
  public ContestResponse start(Long contestId) {
    Contest contest = contestRepository.findById(contestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대회를 찾을 수 없습니다."));
    if (contest.getStatus() != ContestStatus.SCHEDULED) {
      throw new BusinessException(ErrorCode.CONTEST_NOT_JOINABLE, "예정 상태의 대회만 시작할 수 있습니다.");
    }
    contest.start(); // 더티 체킹으로 커밋 때 UPDATE
    return ContestResponse.from(contest, listingRepository.findByIdContestId(contestId).size());
  }
}
