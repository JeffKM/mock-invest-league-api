package com.jeffkm.mockleague.market;

import com.jeffkm.mockleague.common.ApiResponse;
import com.jeffkm.mockleague.market.dto.DisclosureResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공시 목록. 참가하지 않은 사람도 볼 수 있다(시세처럼 공개 정보).
 * limit 의 @Min/@Max 는 Spring 6.1+ 의 기본 메서드 검증이 확인한다(어기면 400).
 */
@RestController
@RequestMapping("/api/contests/{contestId}/disclosures")
public class DisclosureController {

  private final DisclosureService disclosureService;

  public DisclosureController(DisclosureService disclosureService) {
    this.disclosureService = disclosureService;
  }

  @GetMapping
  public ApiResponse<List<DisclosureResponse>> list(@PathVariable Long contestId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return ApiResponse.ok(disclosureService.visible(contestId, limit));
  }
}
