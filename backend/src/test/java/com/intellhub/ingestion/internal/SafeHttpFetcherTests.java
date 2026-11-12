package com.intellhub.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellhub.ingestion.internal.FetchOutcome.Failure;
import com.intellhub.ingestion.internal.FetchOutcome.Reason;
import com.intellhub.ingestion.internal.FetchOutcome.Success;
import com.intellhub.shared.PublicAddressPolicy;
import com.intellhub.shared.PublicUrlPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Names such as feed.example are mapped by a fake resolver, so no test uses the real network or real DNS.
 * Functional tests allow loopback (the fake server lives there) but nothing else non-public; the
 * blocking tests use the strict production address policy.
 */
class SafeHttpFetcherTests {

	private static final String FEED = "<rss version=\"2.0\"><channel><title>t</title></channel></rss>";

	private static final Predicate<InetAddress> LOOPBACK_OR_PUBLIC = a -> a.isLoopbackAddress()
			|| PublicAddressPolicy.isPublic(a);

	private FakeFeedServer server;

	private FakeFeedServer internal;

	private final Map<String, InetAddress[]> hosts = new HashMap<>();

	private final AtomicInteger lookups = new AtomicInteger();

	private SafeHttpFetcher fetcher;

	@BeforeEach
	void setUp() throws Exception {
		server = new FakeFeedServer();
		internal = new FakeFeedServer();
		InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
		hosts.put("feed.example", new InetAddress[] { loopback });
		hosts.put("other.example", new InetAddress[] { loopback });
		hosts.put("private.example", new InetAddress[] { InetAddress.getByAddress(new byte[] { 10, 0, 0, 5 }) });
		hosts.put("metadata.example", new InetAddress[] { InetAddress.getByAddress(new byte[] { (byte) 169, (byte) 254, (byte) 169, (byte) 254 }) });
		hosts.put("mixed.example", new InetAddress[] { loopback, InetAddress.getByAddress(new byte[] { 10, 0, 0, 5 }) });
		hosts.put("rebind.example", new InetAddress[] { loopback });
		fetcher = lenient(settings());
	}

	@AfterEach
	void tearDown() {
		fetcher.close();
		server.close();
		internal.close();
	}

	private static FetchSettings settings() {
		return new FetchSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(3), 1_000_000, 3,
				"IntellHub/test");
	}

	private DnsResolver fakeDns() {
		return new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				lookups.incrementAndGet();
				InetAddress[] found = hosts.get(host);
				if (found == null) {
					throw new UnknownHostException("unknown");
				}
				return found;
			}

			@Override
			public String resolveCanonicalHostname(String host) {
				return host;
			}
		};
	}

	private SafeHttpFetcher lenient(FetchSettings s) {
		return new SafeHttpFetcher(s, PublicUrlPolicy::problem, LOOPBACK_OR_PUBLIC, fakeDns());
	}

	private SafeHttpFetcher strict(FetchSettings s) {
		return new SafeHttpFetcher(s, PublicUrlPolicy::problem, PublicAddressPolicy::isPublic, fakeDns());
	}

	private String url(String host, String path) {
		return "http://" + host + ":" + server.port() + path;
	}

	private static Reason reason(FetchOutcome outcome) {
		assertThat(outcome).isInstanceOf(Failure.class);
		return ((Failure) outcome).reason();
	}

	// ---- the normal path ----

	@Test
	void fetchesABodyAndReportsTheFinalUrlAndType() {
		server.on("/feed", FakeFeedServer.body(200, "application/rss+xml; charset=utf-8", FEED));

		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/feed"));

		assertThat(outcome).isInstanceOf(Success.class);
		Success ok = (Success) outcome;
		assertThat(new String(ok.body())).isEqualTo(FEED);
		assertThat(ok.finalUrl()).isEqualTo(url("feed.example", "/feed"));
		assertThat(ok.contentType()).startsWith("application/rss+xml");
	}

	@Test
	void anEmptySuccessfulResponseIsAnEmptyBody() {
		server.on("/empty", FakeFeedServer.body(204, "text/plain", ""));

		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/empty"));

		assertThat(outcome).isInstanceOf(Success.class);
		assertThat(((Success) outcome).body()).isEmpty();
	}

	@Test
	void requestsCarryNoCookiesOrCredentialsAndIdentifyThemselves() {
		server.on("/start", FakeFeedServer.redirectWithCookie(302, "/next"))
				.on("/next", FakeFeedServer.body(200, "application/xml", FEED));

		fetcher.fetch(url("feed.example", "/start"));

		assertThat(server.requests()).hasSize(2);
		assertThat(server.requests()).allSatisfy(seen -> {
			assertThat(seen.hasHeader("Cookie")).isFalse();
			assertThat(seen.hasHeader("Authorization")).isFalse();
			assertThat(seen.header("User-Agent")).isEqualTo("IntellHub/test");
			assertThat(seen.header("Accept")).contains("application/rss+xml");
		});
	}

	@Test
	void followsRedirectsAndReportsWhereItEndedUp() {
		server.on("/a", FakeFeedServer.redirect(301, "/b"))
				.on("/b", FakeFeedServer.redirect(302, "http://other.example:" + server.port() + "/c"))
				.on("/c", FakeFeedServer.body(200, "application/xml", FEED));

		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/a"));

		assertThat(outcome).isInstanceOf(Success.class);
		assertThat(((Success) outcome).finalUrl()).isEqualTo(url("other.example", "/c"));
	}

	@Test
	void allRedirectStatusesThatCarryALocationAreFollowed() {
		for (int status : new int[] { 301, 302, 303, 307, 308 }) {
			String path = "/r" + status;
			server.on(path, FakeFeedServer.redirect(status, "/target"));
		}
		server.on("/target", FakeFeedServer.body(200, "application/xml", FEED));

		for (int status : new int[] { 301, 302, 303, 307, 308 }) {
			assertThat(fetcher.fetch(url("feed.example", "/r" + status))).as("status " + status).isInstanceOf(Success.class);
		}
	}

	@Test
	void otherStatusesAreErrorsAndNeverLeakTheBody() {
		server.on("/missing", FakeFeedServer.body(404, "text/html", "SECRET-BODY-TEXT"))
				.on("/broken", FakeFeedServer.body(500, "text/html", "SECRET-BODY-TEXT"))
				.on("/multiple", FakeFeedServer.redirect(300, "/target"))
				.on("/notmodified", FakeFeedServer.body(304, "text/plain", ""));

		for (Map.Entry<String, Integer> expected : Map.of("/missing", 404, "/broken", 500, "/multiple", 300,
				"/notmodified", 304).entrySet()) {
			FetchOutcome outcome = fetcher.fetch(url("feed.example", expected.getKey()));

			assertThat(reason(outcome)).as(expected.getKey()).isEqualTo(Reason.HTTP_ERROR);
			assertThat(((Failure) outcome).httpStatus()).isEqualTo(expected.getValue());
			assertThat(((Failure) outcome).message()).isEqualTo("HTTP " + expected.getValue());
		}
	}

	// ---- redirects: the main SSRF vector ----

	@Test
	void aRedirectToALoopbackLiteralIsRefusedWithoutContactingIt() {
		server.on("/go", FakeFeedServer.redirect(302, "http://127.0.0.1:" + internal.port() + "/secret"));
		internal.on("/secret", FakeFeedServer.body(200, "text/plain", "internal data"));

		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/go"));

		assertThat(reason(outcome)).isEqualTo(Reason.INVALID_URL);
		assertThat(internal.requests()).isEmpty();
	}

	@Test
	void aRedirectToTheCloudMetadataAddressIsRefused() {
		server.on("/go", FakeFeedServer.redirect(302, "http://169.254.169.254/latest/meta-data/"));

		assertThat(reason(fetcher.fetch(url("feed.example", "/go")))).isEqualTo(Reason.INVALID_URL);
	}

	@Test
	void aRedirectToANameThatResolvesToAPrivateAddressIsBlockedAtConnectTime() {
		server.on("/go", FakeFeedServer.redirect(302, "http://private.example:" + internal.port() + "/secret"));
		internal.on("/secret", FakeFeedServer.body(200, "text/plain", "internal data"));

		assertThat(reason(fetcher.fetch(url("feed.example", "/go")))).isEqualTo(Reason.BLOCKED_ADDRESS);
		assertThat(internal.requests()).isEmpty();
	}

	@Test
	void aNameWithAnyNonPublicAddressIsBlockedEvenIfOthersArePublic() {
		server.on("/go", FakeFeedServer.redirect(302, "http://mixed.example:" + server.port() + "/x"))
				.on("/x", FakeFeedServer.body(200, "application/xml", FEED));

		assertThat(reason(fetcher.fetch(url("feed.example", "/go")))).isEqualTo(Reason.BLOCKED_ADDRESS);
		assertThat(server.requestCount("/x")).isZero();
	}

	@Test
	void aRedirectToAMetadataNameIsBlockedToo() {
		server.on("/go", FakeFeedServer.redirect(302, "http://metadata.example/latest"));

		assertThat(reason(fetcher.fetch(url("feed.example", "/go")))).isEqualTo(Reason.BLOCKED_ADDRESS);
	}

	@Test
	void redirectsToOtherSchemesCredentialsOrGarbageAreRefused() {
		String[] targets = { "file:///etc/passwd", "ftp://example.com/feed", "gopher://example.com/", "javascript:alert(1)",
				"http://user:secret@other.example/feed", "http://exa mple.com/", "http://[::1]/", "//127.0.0.1/x" };
		for (int i = 0; i < targets.length; i++) {
			server.on("/t" + i, FakeFeedServer.redirect(302, targets[i]));

			assertThat(reason(fetcher.fetch(url("feed.example", "/t" + i)))).as(targets[i]).isEqualTo(Reason.INVALID_URL);
		}
	}

	@Test
	void aRedirectWithoutALocationIsInvalid() {
		server.on("/go", exchange -> exchange.sendResponseHeaders(302, -1));

		assertThat(reason(fetcher.fetch(url("feed.example", "/go")))).isEqualTo(Reason.INVALID_URL);
	}

	@Test
	void tooManyRedirectsAndLoopsStopAfterTheLimit() {
		server.on("/a", FakeFeedServer.redirect(302, "/b")).on("/b", FakeFeedServer.redirect(302, "/a"));

		assertThat(reason(fetcher.fetch(url("feed.example", "/a")))).isEqualTo(Reason.TOO_MANY_REDIRECTS);
		// the first request plus three redirects
		assertThat(server.requests()).hasSize(4);
	}

	@Test
	void anHttpsToHttpRedirectIsADowngrade() {
		assertThat(SafeHttpFetcher.isDowngrade(URI.create("https://a.example/"), URI.create("http://a.example/"))).isTrue();
		assertThat(SafeHttpFetcher.isDowngrade(URI.create("HTTPS://a.example/"), URI.create("ftp://a.example/"))).isTrue();
		assertThat(SafeHttpFetcher.isDowngrade(URI.create("https://a.example/"), URI.create("https://b.example/"))).isFalse();
		assertThat(SafeHttpFetcher.isDowngrade(URI.create("http://a.example/"), URI.create("https://a.example/"))).isFalse();
		assertThat(SafeHttpFetcher.isDowngrade(URI.create("http://a.example/"), URI.create("http://b.example/"))).isFalse();
	}

	// ---- the start URL and DNS ----

	@Test
	void aStartUrlThatFailsTheTextPolicyNeverConnects() {
		assertThat(reason(fetcher.fetch("http://127.0.0.1:" + internal.port() + "/x"))).isEqualTo(Reason.INVALID_URL);
		assertThat(reason(fetcher.fetch("http://localhost:" + internal.port() + "/x"))).isEqualTo(Reason.INVALID_URL);
		assertThat(reason(fetcher.fetch("ftp://feed.example/x"))).isEqualTo(Reason.INVALID_URL);
		assertThat(reason(fetcher.fetch("not a url"))).isEqualTo(Reason.INVALID_URL);
		assertThat(internal.requests()).isEmpty();
		assertThat(lookups.get()).isZero();
	}

	@Test
	void aNameThatResolvesToAPrivateAddressIsBlockedWithoutAnyConnection() {
		FetchOutcome outcome = fetcher.fetch(url("private.example", "/feed"));

		assertThat(reason(outcome)).isEqualTo(Reason.BLOCKED_ADDRESS);
		assertThat(((Failure) outcome).message()).isEqualTo("Blocked address");
	}

	@Test
	void anUnknownNameIsAnOrdinaryNetworkError() {
		assertThat(reason(fetcher.fetch(url("nowhere.example", "/feed")))).isEqualTo(Reason.NETWORK_ERROR);
	}

	@Test
	void theStrictProductionPolicyBlocksLoopbackReachedByName() {
		try (SafeHttpFetcher production = strict(settings())) {
			server.on("/feed", FakeFeedServer.body(200, "application/xml", FEED));

			assertThat(reason(production.fetch(url("rebind.example", "/feed")))).isEqualTo(Reason.BLOCKED_ADDRESS);
			assertThat(server.requests()).isEmpty();
		}
	}

	@Test
	void eachHopUsesASingleLookupSoRebindingHasNothingToExploit() {
		// A hostile resolver answers differently the second time. Only one lookup is ever made per hop,
		// and the connection uses that answer, so the second answer is never used.
		AtomicInteger calls = new AtomicInteger();
		DnsResolver rebinding = new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				try {
					byte[] address = calls.incrementAndGet() == 1 ? new byte[] { 127, 0, 0, 1 } : new byte[] { 10, 0, 0, 5 };
					return new InetAddress[] { InetAddress.getByAddress(address) };
				}
				catch (UnknownHostException e) {
					throw e;
				}
			}

			@Override
			public String resolveCanonicalHostname(String host) {
				return host;
			}
		};
		server.on("/feed", FakeFeedServer.body(200, "application/xml", FEED));
		try (SafeHttpFetcher f = new SafeHttpFetcher(settings(), PublicUrlPolicy::problem, LOOPBACK_OR_PUBLIC, rebinding)) {
			FetchOutcome outcome = f.fetch(url("rebind.example", "/feed"));

			assertThat(outcome).isInstanceOf(Success.class);
			assertThat(calls.get()).isEqualTo(1);
		}
	}

	// ---- size, compression and time ----

	@Test
	void aBodyOverTheLimitIsRejectedWhenItDeclaresItsSize() {
		server.on("/big", FakeFeedServer.body(200, "application/xml", "x".repeat(5000)));
		try (SafeHttpFetcher small = lenient(new FetchSettings(Duration.ofSeconds(1), Duration.ofSeconds(1),
				Duration.ofSeconds(3), 1000, 3, "IntellHub/test"))) {
			assertThat(reason(small.fetch(url("feed.example", "/big")))).isEqualTo(Reason.TOO_LARGE);
		}
	}

	@Test
	void aBodyThatNeverEndsIsCutOffAtTheLimit() {
		server.on("/endless", FakeFeedServer.endless());

		long start = System.nanoTime();
		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/endless"));

		assertThat(reason(outcome)).isEqualTo(Reason.TOO_LARGE);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void aCompressionBombIsCutOffAtTheDecodedLimit() {
		server.on("/bomb", FakeFeedServer.gzipBomb());

		long start = System.nanoTime();
		FetchOutcome outcome = fetcher.fetch(url("feed.example", "/bomb"));

		assertThat(reason(outcome)).isEqualTo(Reason.TOO_LARGE);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void aSlowResponseHitsTheResponseTimeout() {
		server.on("/slow", FakeFeedServer.sleepThenBody(2500, FEED));
		try (SafeHttpFetcher impatient = lenient(new FetchSettings(Duration.ofSeconds(1), Duration.ofMillis(300),
				Duration.ofSeconds(5), 1_000_000, 3, "IntellHub/test"))) {
			assertThat(reason(impatient.fetch(url("feed.example", "/slow")))).isEqualTo(Reason.TIMEOUT);
		}
	}

	@Test
	void aServerThatTricklesIsStoppedByTheTotalDeadline() {
		// One byte every 100 ms never trips the per-read timeout, so only the total deadline can stop it.
		server.on("/trickle", FakeFeedServer.trickle(100, 200));
		try (SafeHttpFetcher patient = lenient(new FetchSettings(Duration.ofSeconds(1), Duration.ofSeconds(1),
				Duration.ofMillis(700), 1_000_000, 3, "IntellHub/test"))) {
			long start = System.nanoTime();

			FetchOutcome outcome = patient.fetch(url("feed.example", "/trickle"));

			assertThat(reason(outcome)).isEqualTo(Reason.TIMEOUT);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
		}
	}

	@Test
	void failureMessagesAreFixedTextOnly() {
		for (Reason reason : Reason.values()) {
			String message = new Failure(reason, 418).message();
			assertThat(message).isNotBlank().doesNotContain("http://").doesNotContain("Exception");
		}
	}

}
