package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.TestcontainersConfiguration;
import com.intellhub.shared.ApiException;
import com.intellhub.sources.FetchResult;
import com.intellhub.sources.SourceCatalog;
import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SourceServiceTests {

	private final SourceService service;

	private final SourceCatalog catalog;

	private final SourceRepository repository;

	private final PlatformTransactionManager transactionManager;

	SourceServiceTests(SourceService service, SourceCatalog catalog, SourceRepository repository,
			PlatformTransactionManager transactionManager) {
		this.service = service;
		this.catalog = catalog;
		this.repository = repository;
		this.transactionManager = transactionManager;
	}

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private Source rss(String name, String url) {
		return service.create(SourceType.RSS, name, url, Map.of());
	}

	@Test
	void createStoresACleanedSourceWithHealthDefaults() {
		Source created = service.create(SourceType.RSS, "  My Feed  ", "HTTPS://Example.COM/Feed.xml?X=1",
				Map.of("note", "x"));

		Source loaded = repository.findById(created.getId()).orElseThrow();
		assertThat(loaded.getName()).isEqualTo("My Feed");
		assertThat(loaded.getUrl()).isEqualTo("https://example.com/Feed.xml?X=1");
		assertThat(loaded.getConfig()).containsEntry("note", "x");
		assertThat(loaded.isEnabled()).isTrue();
		assertThat(loaded.getConsecutiveFailures()).isZero();
	}

	@Test
	void theSameFeedCannotBeAddedTwiceEvenWithDifferentHostCase() {
		rss("A", "https://example.com/feed");

		assertThatThrownBy(() -> rss("B", "https://EXAMPLE.com/feed"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_EXISTS"));
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void invalidInputIsRejectedBeforeAnythingIsStored() {
		assertThatThrownBy(() -> rss("A", "http://169.254.169.254/latest"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_URL"));
		assertThatThrownBy(() -> rss("  ", "https://example.com/feed")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> rss("x".repeat(121), "https://example.com/feed")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> rss("bad\nname", "https://example.com/feed")).isInstanceOf(ApiException.class);
		Map<String, Object> huge = new HashMap<>();
		huge.put("blob", "x".repeat(5000));
		assertThatThrownBy(() -> service.create(SourceType.RSS, "A", "https://example.com/feed", huge))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_CONFIG"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void updateChangesOnlyTheGivenFieldsAndNeverTheTypeOrUrl() {
		Source created = service.create(SourceType.RSS, "Old", "https://example.com/feed", Map.of("a", 1));

		service.update(created.getId(), "New", null, null);
		Source afterRename = repository.findById(created.getId()).orElseThrow();
		assertThat(afterRename.getName()).isEqualTo("New");
		assertThat(afterRename.isEnabled()).isTrue();
		assertThat(afterRename.getConfig()).containsEntry("a", 1);

		service.update(created.getId(), null, false, Map.of("b", 2));
		Source afterToggle = repository.findById(created.getId()).orElseThrow();
		assertThat(afterToggle.getName()).isEqualTo("New");
		assertThat(afterToggle.isEnabled()).isFalse();
		assertThat(afterToggle.getConfig()).containsOnlyKeys("b");
		assertThat(afterToggle.getType()).isEqualTo(SourceType.RSS);
		assertThat(afterToggle.getUrl()).isEqualTo("https://example.com/feed");
	}

	@Test
	void updateAndDeleteOfAnUnknownIdAreNotFound() {
		UUID missing = UUID.randomUUID();

		assertThatThrownBy(() -> service.update(missing, "x", null, null))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_NOT_FOUND"));
		assertThatThrownBy(() -> service.delete(missing)).isInstanceOf(ApiException.class);
	}

	@Test
	void deleteRemovesTheSource() {
		Source created = rss("A", "https://example.com/feed");

		service.delete(created.getId());

		assertThat(repository.findById(created.getId())).isEmpty();
	}

	@Test
	void ingestionSeesOnlyEnabledSourcesWithReadOnlyConfig() {
		Source on = service.create(SourceType.RSS, "On", "https://on.example/feed", Map.of("k", "v"));
		Source off = rss("Off", "https://off.example/feed");
		service.update(off.getId(), null, false, null);

		List<SourceInfo> visible = catalog.enabledSources();

		assertThat(visible).extracting(SourceInfo::id).containsExactly(on.getId());
		assertThat(visible.get(0).config()).containsEntry("k", "v");
		assertThatThrownBy(() -> visible.get(0).config().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void aSuccessfulFetchRecordsTheTimeAndClearsTheFailures() {
		Source created = rss("A", "https://example.com/feed");
		catalog.recordFetchResult(created.getId(), FetchResult.failed("timeout"));
		catalog.recordFetchResult(created.getId(), FetchResult.failed("timeout"));
		Instant before = Instant.now();

		catalog.recordFetchResult(created.getId(), FetchResult.ok());

		Source loaded = repository.findById(created.getId()).orElseThrow();
		assertThat(loaded.getLastStatus()).isEqualTo(FetchStatus.OK);
		assertThat(loaded.getConsecutiveFailures()).isZero();
		assertThat(loaded.getLastError()).isNull();
		assertThat(loaded.getLastFetchedAt()).isAfterOrEqualTo(before.minusSeconds(1));
	}

	@Test
	void failuresAccumulateAndKeepTheLatestCleanedError() {
		Source created = rss("A", "https://example.com/feed");

		catalog.recordFetchResult(created.getId(), FetchResult.failed("first"));
		catalog.recordFetchResult(created.getId(), FetchResult.failed("line1\nline2\u0000 " + "x".repeat(900)));

		Source loaded = repository.findById(created.getId()).orElseThrow();
		assertThat(loaded.getLastStatus()).isEqualTo(FetchStatus.FAILED);
		assertThat(loaded.getConsecutiveFailures()).isEqualTo(2);
		assertThat(loaded.getLastError()).hasSize(SourceService.MAX_ERROR_LENGTH).startsWith("line1 line2")
				.doesNotContain("\n").doesNotContain("\u0000");
	}

	@Test
	void aBlankFailureReasonIsStoredAsUnknown() {
		Source created = rss("A", "https://example.com/feed");

		catalog.recordFetchResult(created.getId(), FetchResult.failed("  "));

		assertThat(repository.findById(created.getId()).orElseThrow().getLastError()).isEqualTo("Unknown error");
	}

	@Test
	void resultsForUnknownSourcesAreIgnored() {
		catalog.recordFetchResult(UUID.randomUUID(), FetchResult.ok());
		catalog.recordFetchResult(UUID.randomUUID(), FetchResult.failed("boom"));
	}

	@Test
	void editingASourceDoesNotUndoAFetchReportRecordedMeanwhile() throws Exception {
		Source created = rss("Before", "https://example.com/feed");
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		CountDownLatch loaded = new CountDownLatch(1);
		CountDownLatch reported = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// The editor loads the source (health counters at zero), waits, then saves a rename.
			Future<?> editor = pool.submit(() -> tx.executeWithoutResult(status -> {
				Source loadedSource = repository.findById(created.getId()).orElseThrow();
				loaded.countDown();
				try {
					reported.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				loadedSource.rename("After");
			}));
			loaded.await();
			catalog.recordFetchResult(created.getId(), FetchResult.failed("boom"));
			reported.countDown();
			editor.get();
		}
		finally {
			pool.shutdownNow();
		}

		Source after = repository.findById(created.getId()).orElseThrow();
		assertThat(after.getName()).isEqualTo("After");
		assertThat(after.getConsecutiveFailures()).isEqualTo(1);
		assertThat(after.getLastStatus()).isEqualTo(FetchStatus.FAILED);
		assertThat(after.getLastError()).isEqualTo("boom");
	}

	@Test
	void equivalentUrlsAreTheSameSource() {
		rss("A", "https://example.com/feed");

		for (String equivalent : new String[] { "https://EXAMPLE.com/feed", "https://example.com./feed",
				"https://example.com:443/feed", "HTTPS://Example.Com.:443/feed" }) {
			assertThatThrownBy(() -> rss("B", equivalent)).as(equivalent)
					.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_EXISTS"));
		}
		// A different port, scheme, path or query is a different source.
		rss("C", "https://example.com:8443/feed");
		rss("D", "http://example.com/feed");
		rss("E", "https://example.com/feed?x=1");
		assertThat(repository.count()).isEqualTo(4);
	}

	@Test
	void normalizeUrlOnlyTouchesSchemeHostAndDefaultPort() {
		assertThat(SourceService.normalizeUrl("HTTP://Example.COM:80/Path/Feed?Q=A#Frag"))
				.isEqualTo("http://example.com/Path/Feed?Q=A#Frag");
		assertThat(SourceService.normalizeUrl("https://example.com:/x")).isEqualTo("https://example.com/x");
		assertThat(SourceService.normalizeUrl("https://example.com:8443")).isEqualTo("https://example.com:8443");
		assertThat(SourceService.normalizeUrl("https://[2606:4700:4700::1111]:443/f"))
				.isEqualTo("https://[2606:4700:4700::1111]/f");
	}

	@Test
	void aMissingTypeIsABadRequest() {
		assertThatThrownBy(() -> service.create(null, "A", "https://example.com/feed", Map.of()))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(400));
	}

	@Test
	void nestedConfigGivenToIngestionIsReadOnlyAtEveryLevel() {
		service.create(SourceType.RSS, "A", "https://example.com/feed",
				Map.of("tags", new ArrayList<>(List.of("ai")), "nested", new HashMap<>(Map.of("k", "v"))));

		Map<String, Object> config = catalog.enabledSources().get(0).config();

		assertThatThrownBy(() -> ((List<Object>) config.get("tags")).add("x"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> ((Map<String, Object>) config.get("nested")).put("x", "y"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThat(config).containsEntry("tags", List.of("ai"));
	}

	@Test
	void errorTextHasNoUnicodeLineBreaksAndNeverEndsInHalfAnEmoji() {
		Source created = rss("A", "https://example.com/feed");

		catalog.recordFetchResult(created.getId(), FetchResult.failed("a\u2028b\u2029c\u0085d"));
		assertThat(repository.findById(created.getId()).orElseThrow().getLastError()).isEqualTo("a b c d");

		String boundary = "x".repeat(SourceService.MAX_ERROR_LENGTH - 1) + "\uD83D\uDE00" + "tail";
		catalog.recordFetchResult(created.getId(), FetchResult.failed(boundary));
		String stored = repository.findById(created.getId()).orElseThrow().getLastError();
		assertThat(stored).hasSize(SourceService.MAX_ERROR_LENGTH - 1);
		assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
	}

	@Test
	void concurrentFailureReportsAreAllCounted() throws Exception {
		Source created = rss("A", "https://example.com/feed");
		int threads = 20;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					catalog.recordFetchResult(created.getId(), FetchResult.failed("boom"));
					return null;
				}));
			}
			ready.await();
			go.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(repository.findById(created.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(threads);
	}

}
