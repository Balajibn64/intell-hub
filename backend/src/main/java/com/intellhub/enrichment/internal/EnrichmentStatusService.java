package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.EnrichmentSnapshot;
import com.intellhub.enrichment.EnrichmentSnapshot.ProviderUsage;
import com.intellhub.enrichment.EnrichmentStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EnrichmentStatusService implements EnrichmentStatus {

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EnrichmentProperties properties;

	private final ProviderChain chain;

	EnrichmentStatusService(JdbcClient jdbc, Clock clock, EnrichmentProperties properties, ProviderChain chain) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.properties = properties;
		this.chain = chain;
	}

	@Override
	@Transactional(readOnly = true)
	public EnrichmentSnapshot snapshot() {
		LocalDate day = LocalDate.ofInstant(clock.instant(), properties.budgetZone());
		List<ProviderUsage> usage = jdbc.sql("select provider, calls, failures from llm_usage where day = :day order by provider")
				.param("day", day)
				.query((rs, n) -> new ProviderUsage(rs.getString("provider"), rs.getInt("calls"), rs.getInt("failures"))).list();
		// Everything below comes from the one day and the one list of rows read above, so the page cannot mix two days.
		int used = usage.stream().mapToInt(ProviderUsage::calls).sum();
		return new EnrichmentSnapshot(properties.enabled(), chain.providerNames(), day, properties.dailyBudget(),
				properties.reservedCalls(), used, Math.max(0, properties.dailyBudget() - used),
				Math.max(0, properties.dailyBudget() - properties.reservedCalls() - used), usage);
	}

}
