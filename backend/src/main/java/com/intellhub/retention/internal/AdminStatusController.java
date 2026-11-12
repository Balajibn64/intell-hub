package com.intellhub.retention.internal;

import com.intellhub.articles.ArticleStatistics;
import com.intellhub.enrichment.EnrichmentSnapshot;
import com.intellhub.enrichment.EnrichmentStatus;
import com.intellhub.retention.RetentionResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceHealthReport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One read-only page for an administrator: how the sources are doing, where the articles stand, how much of
 * today's model budget is used, and what the last purge did. It takes no input and shows no secrets, addresses,
 * settings or article text.
 */
@RestController
@RequestMapping("/api/v1/admin/status")
@PreAuthorize("hasRole('ADMIN')")
class AdminStatusController {

	private final SourceCatalog sources;

	private final ArticleStatistics articles;

	private final EnrichmentStatus enrichment;

	private final RetentionService retention;

	AdminStatusController(SourceCatalog sources, ArticleStatistics articles, EnrichmentStatus enrichment, RetentionService retention) {
		this.sources = sources;
		this.articles = articles;
		this.enrichment = enrichment;
		this.retention = retention;
	}

	record Status(SourcesStatus sources, ArticlesStatus articles, EnrichmentStatusView enrichment, RetentionStatus retention) {
	}

	record SourcesStatus(int total, int enabled, int failing, int neverFetched, List<SourceHealthReport.SourceHealth> items) {
	}

	record ArticlesStatus(long total, Map<String, Long> byStatus, Instant oldestPublishedAt) {
	}

	record EnrichmentStatusView(boolean enabled, List<String> providers, LocalDate day, Budget budget,
			List<EnrichmentSnapshot.ProviderUsage> usageToday) {
	}

	record Budget(int daily, int reservedForReaders, int usedToday, int remainingForReaders, int remainingForBackground) {
	}

	/** {@code lastRun} is null until a purge has run since the app started. */
	record RetentionStatus(boolean scheduled, long windowDays, RetentionResult lastRun) {
	}

	@GetMapping
	Status status() {
		SourceHealthReport health = sources.health();
		EnrichmentSnapshot e = enrichment.snapshot();
		return new Status(
				new SourcesStatus(health.total(), health.enabled(), health.failing(), health.neverFetched(), health.sources()),
				new ArticlesStatus(articles.total(), articles.countsByStatus(), articles.oldestPublishedAt().orElse(null)),
				new EnrichmentStatusView(e.enabled(), e.providers(), e.day(),
						new Budget(e.dailyBudget(), e.reservedCalls(), e.usedToday(), e.remainingForReaders(), e.remainingForBackground()),
						e.usageToday()),
				new RetentionStatus(retention.properties().enabled(), retention.properties().window().toDays(),
						retention.lastRun().orElse(null)));
	}

}
