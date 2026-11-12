package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.articles.ArticleStore;
import com.intellhub.articles.NewArticle;
import com.intellhub.articles.StoreOutcome;
import com.intellhub.ingestion.internal.IngestSummary.SourceResult;
import com.intellhub.ingestion.internal.IngestSummary.Status;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** What happens when the feed is fine but storing its entries is not. */
@Import({ TestcontainersConfiguration.class, IngestionServiceTests.TestWiring.class })
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@ExtendWith(OutputCaptureExtension.class)
class IngestionStorageFailureTests {

	@MockitoBean
	ArticleStore articles;

	private final IngestionService ingestion;

	private final JdbcTemplate jdbc;

	private FakeFeedServer server;

	IngestionStorageFailureTests(IngestionService ingestion, JdbcTemplate jdbc) {
		this.ingestion = ingestion;
		this.jdbc = jdbc;
	}

	@BeforeEach
	void setUp() throws Exception {
		jdbc.update("delete from sources");
		server = new FakeFeedServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private UUID source(String path) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into sources (id, type, name, url, created_at) values (?, 'RSS', 'S', ?, now())", id,
				"http://feed.example:" + server.port() + path);
		return id;
	}

	private static String feed(String... titles) {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < titles.length; i++) {
			items.append("<item><title>").append(titles[i]).append("</title><link>https://example.com/").append(i)
					.append("</link><description>x</description></item>");
		}
		return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title><link>https://example.com/</link>"
				+ "<description>d</description>" + items + "</channel></rss>";
	}

	private Map<String, Object> health(UUID id) {
		return jdbc.queryForMap("select last_status, last_error, consecutive_failures from sources where id = ?", id);
	}

	@Test
	void aSourceWhoseEntriesAllFailToStoreIsAFailureAndTheClassIsLogged(CapturedOutput output) {
		when(articles.store(any())).thenThrow(new IllegalStateException("db down with SECRET-FEED-TEXT"));
		server.on("/feed", FakeFeedServer.body(200, "application/rss+xml", feed("One", "Two", "Three")));
		UUID id = source("/feed");

		IngestSummary summary = ingestion.run();

		SourceResult result = summary.sources().get(0);
		assertThat(result.status()).isEqualTo(Status.FAILED);
		assertThat(result.error()).isEqualTo("Could not store entries");
		assertThat(health(id).get("last_status")).isEqualTo("FAILED");
		assertThat(health(id).get("last_error")).isEqualTo("Could not store entries");
		assertThat(output.getAll()).contains("3 of 3 entries could not be stored").contains("IllegalStateException")
				.doesNotContain("SECRET-FEED-TEXT");
	}

	@Test
	void someEntriesFailingKeepsTheSourceHealthyButIsCountedAndLogged(CapturedOutput output) {
		when(articles.store(any())).thenAnswer(invocation -> {
			NewArticle article = invocation.getArgument(0);
			if (article.title().startsWith("bad")) {
				throw new IllegalArgumentException("rejected SECRET-FEED-TEXT");
			}
			return StoreOutcome.CREATED;
		});
		server.on("/feed", FakeFeedServer.body(200, "application/rss+xml", feed("good one", "bad one", "good two")));
		UUID id = source("/feed");

		IngestSummary summary = ingestion.run();

		SourceResult result = summary.sources().get(0);
		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.created()).isEqualTo(2);
		assertThat(result.errors()).isEqualTo(1);
		assertThat(health(id).get("last_status")).isEqualTo("OK");
		assertThat(output.getAll()).contains("1 of 3 entries could not be stored").contains("IllegalArgumentException")
				.doesNotContain("SECRET-FEED-TEXT");
	}

}
