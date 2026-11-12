package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LlmBudgetTests {

	private static final Instant NOON = Instant.parse("2026-11-12T12:00:00Z");

	private final JdbcClient jdbcClient;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final MutableClock clock = new MutableClock(NOON);

	LlmBudgetTests(JdbcClient jdbcClient, JdbcTemplate jdbc, TransactionTemplate transaction) {
		this.jdbcClient = jdbcClient;
		this.jdbc = jdbc;
		this.transaction = transaction;
	}

	@BeforeEach
	void clean() {
		jdbc.update("delete from llm_usage");
		clock.advance(Duration.between(clock.instant(), NOON));
	}

	private static EnrichmentProperties props(int budget, int reserved, String zone) {
		return new EnrichmentProperties("fake", budget, reserved, 5, 3, Duration.ofSeconds(30), Duration.ofMinutes(10), 20,
				ZoneId.of(zone), 5000, 0.15);
	}

	private LlmBudget budget(int budget, int reserved) {
		return new LlmBudget(jdbcClient, clock, props(budget, reserved, "UTC"), transaction);
	}

	private int grants(LlmBudget b, BudgetPriority priority, int attempts, String provider) {
		int granted = 0;
		for (int i = 0; i < attempts; i++) {
			if (b.tryReserve(priority, provider)) {
				granted++;
			}
		}
		return granted;
	}

	@Test
	void backgroundWorkGetsTheBudgetMinusTheReserve() {
		LlmBudget b = budget(10, 3);

		assertThat(grants(b, BudgetPriority.NORMAL, 20, "fake")).isEqualTo(7);

		assertThat(b.usedToday()).isEqualTo(7);
		assertThat(b.remainingToday(BudgetPriority.NORMAL)).isZero();
		assertThat(b.remainingToday(BudgetPriority.RESERVED)).isEqualTo(3);
	}

	@Test
	void onlyReadersRequestsMaySpendTheReserveAndNeverBeyondTheWholeBudget() {
		LlmBudget b = budget(10, 3);
		grants(b, BudgetPriority.NORMAL, 20, "fake");

		assertThat(b.tryReserve(BudgetPriority.NORMAL, "fake")).isFalse();
		assertThat(grants(b, BudgetPriority.RESERVED, 10, "fake")).isEqualTo(3);

		assertThat(b.usedToday()).isEqualTo(10);
		assertThat(b.tryReserve(BudgetPriority.RESERVED, "fake")).isFalse();
		assertThat(b.tryReserve(BudgetPriority.NORMAL, "fake")).isFalse();
		assertThat(b.remainingToday(BudgetPriority.RESERVED)).isZero();
	}

	@Test
	void withNoReserveBackgroundWorkMayUseEverything() {
		LlmBudget b = budget(4, 0);

		assertThat(grants(b, BudgetPriority.NORMAL, 10, "fake")).isEqualTo(4);
	}

	@Test
	void providersShareOneCapAndAreCountedSeparately() {
		LlmBudget b = budget(6, 0);

		assertThat(grants(b, BudgetPriority.NORMAL, 4, "gemini")).isEqualTo(4);
		assertThat(grants(b, BudgetPriority.NORMAL, 4, "groq")).isEqualTo(2);

		assertThat(jdbc.queryForObject("select calls from llm_usage where provider = 'gemini'", Integer.class)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select calls from llm_usage where provider = 'groq'", Integer.class)).isEqualTo(2);
		assertThat(b.usedToday()).isEqualTo(6);
	}

	@Test
	void failuresAreCountedSeparatelyAndDoNotSpendACall() {
		LlmBudget b = budget(10, 0);
		b.tryReserve(BudgetPriority.NORMAL, "fake");

		b.recordFailure("fake");
		b.recordFailure("fake");
		b.recordFailure("other");

		assertThat(b.usedToday()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select failures from llm_usage where provider = 'fake'", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select failures from llm_usage where provider = 'other'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select calls from llm_usage where provider = 'other'", Integer.class)).isZero();
	}

	@Test
	void theCountSurvivesARestart() {
		budget(10, 0).tryReserve(BudgetPriority.NORMAL, "fake");
		budget(10, 0).tryReserve(BudgetPriority.NORMAL, "fake");

		LlmBudget afterRestart = budget(10, 0);

		assertThat(afterRestart.usedToday()).isEqualTo(2);
		assertThat(grants(afterRestart, BudgetPriority.NORMAL, 20, "fake")).isEqualTo(8);
	}

	@Test
	void aNewDayGetsAFreshBudgetAndOldUsageIsKept() {
		LlmBudget b = budget(3, 0);
		assertThat(grants(b, BudgetPriority.NORMAL, 10, "fake")).isEqualTo(3);

		clock.advance(Duration.ofHours(13)); // 01:00 the next day in UTC

		assertThat(b.usedToday()).isZero();
		assertThat(grants(b, BudgetPriority.NORMAL, 10, "fake")).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from llm_usage", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select sum(calls) from llm_usage", Integer.class)).isEqualTo(6);
	}

	@Test
	void theDayStartsAtMidnightInTheConfiguredZone() {
		// In November Los Angeles is UTC-8: 07:30 UTC is 23:30 on the 11th there, and midnight falls at 08:00 UTC.
		MutableClock la = new MutableClock(Instant.parse("2026-11-12T07:30:00Z"));
		LlmBudget b = new LlmBudget(jdbcClient, la, props(2, 0, "America/Los_Angeles"), transaction);
		assertThat(grants(b, BudgetPriority.NORMAL, 5, "fake")).isEqualTo(2);

		la.advance(Duration.ofMinutes(20)); // 07:50 UTC: still the 11th in Los Angeles
		assertThat(b.tryReserve(BudgetPriority.NORMAL, "fake")).isFalse();

		la.advance(Duration.ofHours(1)); // 08:50 UTC: now the 12th there
		assertThat(b.tryReserve(BudgetPriority.NORMAL, "fake")).isTrue();
	}

	@Test
	void manyCallersAtOnceAreGrantedExactlyTheCap() throws Exception {
		LlmBudget b = budget(20, 3);
		int threads = 40;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return b.tryReserve(BudgetPriority.NORMAL, "fake");
				}));
			}
			ready.await();
			go.countDown();
			int granted = 0;
			for (Future<Boolean> future : futures) {
				if (future.get()) {
					granted++;
				}
			}
			assertThat(granted).isEqualTo(17);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(b.usedToday()).isEqualTo(17);
	}

	@Test
	void theDatabaseRefusesNegativeCounts() {
		assertThatThrownBy(() -> jdbc.update("insert into llm_usage (day, provider, calls, failures) values (current_date, 'x', -1, 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("insert into llm_usage (day, provider, calls, failures) values (current_date, 'y', 0, -1)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
