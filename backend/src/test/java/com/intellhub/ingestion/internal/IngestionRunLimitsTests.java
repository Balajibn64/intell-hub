package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.ingestion.internal.IngestSummary.SourceResult;
import com.intellhub.ingestion.internal.IngestSummary.Status;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/** One source at a time and a short run limit, so a slow source runs into the limit. */
@Import({ TestcontainersConfiguration.class, IngestionServiceTests.TestWiring.class })
@SpringBootTest(properties = { "intellhub.ingestion.run-timeout=400ms", "intellhub.ingestion.concurrency=1" })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class IngestionRunLimitsTests {

	private static final String FEED = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>"
			+ "<link>https://example.com/</link><description>d</description><item><title>An item</title>"
			+ "<link>https://example.com/item</link><description>x</description></item></channel></rss>";

	private final IngestionService ingestion;

	private final JdbcTemplate jdbc;

	private FakeFeedServer server;

	IngestionRunLimitsTests(IngestionService ingestion, JdbcTemplate jdbc) {
		this.ingestion = ingestion;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() throws Exception {
		jdbc.update("delete from sources");
		server = new FakeFeedServer();
		server.on("/fast", FakeFeedServer.body(200, "application/rss+xml", FEED))
				.on("/slow", FakeFeedServer.sleepThenBody(1500, FEED));
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private UUID source(String name, String path) throws InterruptedException {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', ?, ?, now())", id, name,
				"http://feed.example:" + server.port() + path);
		Thread.sleep(5); // distinct creation times, so the run order is fixed
		return id;
	}

	private Map<String, Object> health(UUID id) {
		return jdbc.queryForMap("select last_status, last_error, consecutive_failures from sources where id = ?", id);
	}

	@Test
	void aSourceThatRunsIntoTheRunLimitIsReportedAndLeavesItsHealthAloneEvenAfterwards() throws Exception {
		UUID fast = source("Fast", "/fast");
		UUID slow = source("Slow", "/slow");

		IngestSummary summary = ingestion.run();

		SourceResult fastResult = summary.sources().stream().filter(s -> s.sourceId().equals(fast)).findFirst().orElseThrow();
		SourceResult slowResult = summary.sources().stream().filter(s -> s.sourceId().equals(slow)).findFirst().orElseThrow();
		// The finished source is kept in the summary and in the database.
		assertThat(fastResult.status()).isEqualTo(Status.OK);
		assertThat(fastResult.created()).isEqualTo(1);
		assertThat(slowResult.status()).isEqualTo(Status.FAILED);
		assertThat(slowResult.error()).isEqualTo("Run time limit reached");
		// The cancelled source must not write a health record later, even after its fetch eventually ends.
		Thread.sleep(1200);
		assertThat(health(slow).get("last_status")).isNull();
		assertThat(health(slow).get("consecutive_failures")).isEqualTo(0);
		assertThat(health(fast).get("last_status")).isEqualTo("OK");
	}

	@Test
	void anInterruptedRunReportsTheUnfinishedSourcesAndKeepsTheInterruptFlag() throws Exception {
		UUID slow = source("Slow", "/slow");
		Thread.currentThread().interrupt();
		IngestSummary summary;
		try {
			summary = ingestion.run();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted(); // clear the flag so it cannot leak into other tests
		}

		SourceResult result = summary.sources().get(0);
		assertThat(result.sourceId()).isEqualTo(slow);
		assertThat(result.error()).isEqualTo("Run interrupted");
		Thread.sleep(1200);
		assertThat(health(slow).get("last_status")).isNull();
	}

}
