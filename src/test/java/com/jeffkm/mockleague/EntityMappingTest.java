package com.jeffkm.mockleague;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jeffkm.mockleague.account.CashLedger;
import com.jeffkm.mockleague.account.CashLedgerRepository;
import com.jeffkm.mockleague.account.Holding;
import com.jeffkm.mockleague.account.HoldingId;
import com.jeffkm.mockleague.account.HoldingRepository;
import com.jeffkm.mockleague.account.LedgerEntryType;
import com.jeffkm.mockleague.account.Participant;
import com.jeffkm.mockleague.account.ParticipantRepository;
import com.jeffkm.mockleague.company.Company;
import com.jeffkm.mockleague.company.CompanyRepository;
import com.jeffkm.mockleague.contest.Contest;
import com.jeffkm.mockleague.contest.ContestListing;
import com.jeffkm.mockleague.contest.ContestListingRepository;
import com.jeffkm.mockleague.contest.ContestRepository;
import com.jeffkm.mockleague.order.Order;
import com.jeffkm.mockleague.order.OrderRepository;
import com.jeffkm.mockleague.order.OrderSide;
import com.jeffkm.mockleague.order.OrderStatus;
import com.jeffkm.mockleague.support.PostgresTestConfig;
import com.jeffkm.mockleague.user.User;
import com.jeffkm.mockleague.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 엔티티를 실제 DB에 저장하고, 영속성 컨텍스트를 비운 뒤 다시 읽어 값이 그대로인지 확인한다.
 * flush + clear 를 하지 않으면 1차 캐시의 같은 객체를 돌려받아 DB 왕복을 검증하지 못한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
class EntityMappingTest {

  @Autowired EntityManager em;
  @Autowired UserRepository userRepository;
  @Autowired CompanyRepository companyRepository;
  @Autowired ContestRepository contestRepository;
  @Autowired ContestListingRepository listingRepository;
  @Autowired ParticipantRepository participantRepository;
  @Autowired HoldingRepository holdingRepository;
  @Autowired OrderRepository orderRepository;
  @Autowired CashLedgerRepository ledgerRepository;

  private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

  private Company sbsi;
  private Contest contest;
  private Participant participant;

  @BeforeEach
  void setUp() {
    User user = userRepository.save(new User("mapper"));
    sbsi = companyRepository.findByTicker("SBSI").orElseThrow();
    contest = contestRepository.save(
        Contest.withDefaults("12월 리그", DAY, LocalDate.of(2026, 12, 31), 42L));
    participant = participantRepository.save(Participant.join(contest, user));
  }

  private void flushAndClear() {
    em.flush();
    em.clear();
  }

  @Test
  void 대회와_상장_종목_복합키가_저장되고_다시_읽힌다() {
    listingRepository.save(ContestListing.of(contest, sbsi));
    flushAndClear();

    var listings = listingRepository.findByIdContestId(contest.getId());

    assertThat(listings).hasSize(1);
    assertThat(listings.get(0).getBasePrice()).isEqualTo(84_000);
    assertThat(listings.get(0).getCompany().getTicker()).isEqualTo("SBSI"); // 지연 로딩
  }

  @Test
  void 참가자를_대회와_사용자로_찾는다() {
    Long userId = participant.getUser().getId();
    flushAndClear();

    Participant found = participantRepository.findByContestIdAndUserId(contest.getId(), userId).orElseThrow();

    assertThat(found.getCash()).isEqualTo(Contest.DEFAULT_INITIAL_CASH);
    assertThat(found.getJoinedAt()).isNotNull(); // @CreationTimestamp
  }

  @Test
  void 보유_복합키가_저장되고_다시_읽힌다() {
    Holding h = Holding.empty(participant, sbsi);
    h.increase(10, 840_000);
    holdingRepository.save(h);
    flushAndClear();

    Holding found = holdingRepository.findById(new HoldingId(participant.getId(), sbsi.getId())).orElseThrow();

    assertThat(found.getQuantity()).isEqualTo(10);
    assertThat(found.averagePrice()).isEqualTo(84_000);
    assertThat(holdingRepository.findByIdParticipantId(participant.getId())).hasSize(1);
  }

  @Test
  void 체결된_주문과_원장이_저장되고_원장_합계로_현금을_검증할_수_있다() {
    Order order = Order.market(participant, sbsi, OrderSide.BUY, 10, "k-1", DAY, 0);
    order.fill(84_000, 4_200, 0, Instant.now());
    orderRepository.save(order);
    ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.INITIAL, Contest.DEFAULT_INITIAL_CASH, null));
    ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.BUY, 840_000, order));
    ledgerRepository.save(CashLedger.record(participant, LedgerEntryType.FEE, 4_200, order));
    flushAndClear();

    Order found = orderRepository.findByParticipantIdAndIdempotencyKey(participant.getId(), "k-1").orElseThrow();
    long sum = ledgerRepository.sumAmountByParticipantId(participant.getId());

    assertThat(found.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(found.getFilledPrice()).isEqualTo(84_000);
    assertThat(sum).isEqualTo(Contest.DEFAULT_INITIAL_CASH - 840_000 - 4_200);
  }

  @Test
  void 원장이_없으면_합계는_0이다() {
    assertThat(ledgerRepository.sumAmountByParticipantId(participant.getId())).isZero();
  }

  @Test
  void I6_같은_계좌_같은_키의_주문은_DB가_두_번째를_거부한다() {
    orderRepository.save(Order.market(participant, sbsi, OrderSide.BUY, 1, "dup", DAY, 0));

    // IDENTITY 전략은 id 를 DB에서 받아야 하므로 save() 순간 INSERT 가 바로 나간다.
    // 그래서 오류도 flush 가 아니라 두 번째 save() 에서 난다. 리포지토리가 Spring 예외로 바꿔 준다.
    assertThatThrownBy(() -> orderRepository.save(Order.market(participant, sbsi, OrderSide.BUY, 1, "dup", DAY, 0)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void I1_DB의_CHECK_제약은_서비스를_우회한_음수_잔고도_거부한다() {
    em.flush();

    // 엔티티 규칙(withdraw)을 우회해 SQL로 직접 음수를 넣으면 DB가 마지막으로 막는다
    assertThatThrownBy(() -> em.createNativeQuery("update participants set cash = -1 where id = :id")
            .setParameter("id", participant.getId())
            .executeUpdate())
        .isInstanceOfAny(jakarta.persistence.PersistenceException.class,
            DataIntegrityViolationException.class);
  }
}
