package com.intellhub.enrichment.internal;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The persisted daily call budget, shared by all providers. Free-tier limits change and are easy to exceed,
 * so the count lives in the database (it survives restarts) and the check and the increment happen together
 * under an advisory lock, so many callers at once can never be granted more than the cap.
 *
 * <p>Background batches may use the budget except for {@code reservedCalls}, which only a reader's request
 * can spend. A new day starts at midnight in the configured zone.
 */
@Component
class LlmBudget implements CallBudget {

	/** Any fixed number: all budget operations take the same lock. */
	private static final long LOCK_KEY = 7_102_301L;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EnrichmentProperties properties;

	private final TransactionTemplate transaction;

	/**
	 * The transaction is managed explicitly here instead of with an annotation, because the advisory lock only
	 * works inside one transaction: the budget must stay correct however the object is created or called.
	 */
	LlmBudget(JdbcClient jdbc, Clock clock, EnrichmentProperties properties, TransactionTemplate transaction) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.properties = properties;
		this.transaction = transaction;
	}

	@Override
	public boolean tryReserve(BudgetPriority priority, String provider) {
		return Boolean.TRUE.equals(transaction.execute(status -> {
			lock();
			LocalDate day = today();
			int used = usedOn(day);
			int cap = priority == BudgetPriority.RESERVED ? properties.dailyBudget()
					: properties.dailyBudget() - properties.reservedCalls();
			if (used >= cap) {
				return false;
			}
			jdbc.sql("""
					insert into llm_usage (day, provider, calls, failures) values (:day, :provider, 1, 0)
					on conflict (day, provider) do update set calls = llm_usage.calls + 1""")
					.param("day", day).param("provider", provider).update();
			return true;
		}));
	}

	@Override
	public void recordFailure(String provider) {
		transaction.executeWithoutResult(status -> {
			lock();
			jdbc.sql("""
					insert into llm_usage (day, provider, calls, failures) values (:day, :provider, 0, 1)
					on conflict (day, provider) do update set failures = llm_usage.failures + 1""")
					.param("day", today()).param("provider", provider).update();
		});
	}

	/** Calls made today across all providers. */
	int usedToday() {
		return usedOn(today());
	}

	@Override
	public int remaining(BudgetPriority priority) {
		int cap = priority == BudgetPriority.RESERVED ? properties.dailyBudget()
				: properties.dailyBudget() - properties.reservedCalls();
		return Math.max(0, cap - usedToday());
	}

	private int usedOn(LocalDate day) {
		return jdbc.sql("select coalesce(sum(calls), 0) from llm_usage where day = :day").param("day", day)
				.query(Integer.class).single();
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), properties.budgetZone());
	}

	private void lock() {
		jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", LOCK_KEY).query().singleRow();
	}

}
