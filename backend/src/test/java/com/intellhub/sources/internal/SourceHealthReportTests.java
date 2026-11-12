package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.sources.FetchResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceHealthReport;
import com.intellhub.sources.SourceHealthReport.SourceHealth;
import com.intellhub.sources.SourceType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SourceHealthReportTests {

	private final SourceService service;

	private final SourceCatalog catalog;

	private final SourceRepository repository;

	SourceHealthReportTests(SourceService service, SourceCatalog catalog, SourceRepository repository) {
		this.service = service;
		this.catalog = catalog;
		this.repository = repository;
	}

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private Source rss(String name) {
		return service.create(SourceType.RSS, name, "https://" + name.toLowerCase().replace(' ', '-') + ".example.com/feed", Map.of());
	}

	@Test
	void noSourcesIsAnEmptyReport() {
		SourceHealthReport report = catalog.health();

		assertThat(report.total()).isZero();
		assertThat(report.sources()).isEmpty();
		assertThat(report.failing()).isZero();
	}

	@Test
	void countsEnabledFailingAndNeverFetchedSourcesAndListsEachWithItsHealth() {
		Source healthy = rss("Healthy");
		Source failing = rss("Failing");
		Source disabledFailing = rss("Disabled failing");
		Source fresh = rss("Fresh");
		catalog.recordFetchResult(healthy.getId(), FetchResult.ok());
		catalog.recordFetchResult(failing.getId(), FetchResult.failed("Timed out"));
		catalog.recordFetchResult(failing.getId(), FetchResult.failed("Timed out"));
		catalog.recordFetchResult(disabledFailing.getId(), FetchResult.failed("HTTP 500"));
		service.update(disabledFailing.getId(), null, false, null);

		SourceHealthReport report = catalog.health();

		assertThat(report.total()).isEqualTo(4);
		assertThat(report.enabled()).isEqualTo(3);
		assertThat(report.failing()).isEqualTo(1); // only enabled sources count as failing
		assertThat(report.neverFetched()).isEqualTo(1);
		assertThat(report.sources()).extracting(SourceHealth::name).containsExactly("Healthy", "Failing", "Disabled failing", "Fresh");
		SourceHealth f = report.sources().get(1);
		assertThat(f.lastStatus()).isEqualTo("FAILED");
		assertThat(f.lastError()).isEqualTo("Timed out");
		assertThat(f.consecutiveFailures()).isEqualTo(2);
		assertThat(f.lastFetchedAt()).isNotNull();
		SourceHealth h = report.sources().get(0);
		assertThat(h.lastStatus()).isEqualTo("OK");
		assertThat(h.consecutiveFailures()).isZero();
		SourceHealth n = report.sources().get(3);
		assertThat(n.lastStatus()).isNull();
		assertThat(n.lastFetchedAt()).isNull();
		assertThat(report.sources().get(2).enabled()).isFalse();
		assertThat(fresh.getId()).isEqualTo(n.id());
	}

	@Test
	void theReportCarriesNoUrlsOrConfig() {
		rss("Secretive");

		String text = catalog.health().sources().get(0).toString();

		assertThat(text).doesNotContain("example.com").doesNotContain("config");
	}

}
