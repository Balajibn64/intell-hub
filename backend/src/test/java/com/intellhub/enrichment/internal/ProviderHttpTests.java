package com.intellhub.enrichment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderHttpTests {

	private static final String KEY = "sk-secret-key-123";

	private StubProviderServer server;

	@BeforeEach
	void start() throws Exception {
		server = new StubProviderServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private ProviderHttp http(Duration total, int maxBytes) {
		return new ProviderHttp(new ProviderHttp.Settings(URI.create(server.url("/v1/x")), Duration.ofSeconds(2), total, maxBytes));
	}

	private ProviderHttp http() {
		return http(Duration.ofSeconds(5), 1024);
	}

	private ProviderException failure() {
		return catchProviderException(() -> http().postJson("{\"secret-article\":\"text\"}", Map.of("x-key", KEY)));
	}

	private interface Call {
		void run() throws ProviderException;
	}

	private static ProviderException catchProviderException(Call call) {
		try {
			call.run();
		}
		catch (ProviderException e) {
			return e;
		}
		throw new AssertionError("expected a ProviderException");
	}

	@Test
	void returnsTheBodyAndSendsTheHeadersAndJson() throws Exception {
		server.respond(200, "{\"ok\":true}");

		String reply = http().postJson("{\"a\":1}", Map.of("x-key", KEY));

		assertThat(reply).isEqualTo("{\"ok\":true}");
		assertThat(server.lastBody()).isEqualTo("{\"a\":1}");
		assertThat(server.lastHeaders()).containsEntry("x-key", KEY).containsEntry("content-type", "application/json");
		assertThat(server.lastPath()).isEqualTo("/v1/x"); // the key travels in a header, not in the address
	}

	@Test
	void rateLimitCarriesTheRetryAfterSecondsClamped() {
		server.respond(429, "{}").header("retry-after", "7");
		assertThat(failure().getKind()).isEqualTo(Kind.RATE_LIMITED);
		assertThat(failure().getRetryAfter()).isEqualTo(Duration.ofSeconds(7));

		server.header("retry-after", "999999");
		assertThat(failure().getRetryAfter()).isEqualTo(ProviderHttp.MAX_RETRY_AFTER);
	}

	@Test
	void rateLimitWithoutAHeaderUsesGeminiStyleRetryDelayOrNothing() {
		server.respond(429, "{\"error\":{\"details\":[{\"retryDelay\":\"3.5s\"}]}}");
		assertThat(failure().getRetryAfter()).isEqualTo(Duration.ofMillis(3500));

		server.respond(429, "not json at all");
		ProviderException e = failure();
		assertThat(e.getKind()).isEqualTo(Kind.RATE_LIMITED);
		assertThat(e.getRetryAfter()).isNull();
	}

	@Test
	void rateLimitWithAHugeBodyIsStillARateLimit() {
		server.respond(429, "x".repeat(100_000));
		assertThat(failure().getKind()).isEqualTo(Kind.RATE_LIMITED);
	}

	@Test
	void garbageRetryAfterIsIgnored() {
		server.respond(429, "{}").header("retry-after", "soon");
		assertThat(failure().getRetryAfter()).isNull();
	}

	@Test
	void statusCodesMapToKinds() {
		server.respond(500, "oops");
		assertThat(failure().getKind()).isEqualTo(Kind.UNAVAILABLE);
		server.respond(503, "oops");
		assertThat(failure().getKind()).isEqualTo(Kind.UNAVAILABLE);
		server.respond(504, "oops");
		assertThat(failure().getKind()).isEqualTo(Kind.TIMEOUT);
		for (int status : new int[] { 400, 404 }) {
			server.respond(status, "{}");
			assertThat(failure().getKind()).isEqualTo(Kind.BAD_RESPONSE);
		}
		// A wrong or revoked key is our problem, not the article's, so it is not a "bad response".
		for (int status : new int[] { 401, 403 }) {
			server.respond(status, "{}");
			assertThat(failure().getKind()).isEqualTo(Kind.UNAVAILABLE);
		}
	}

	@Test
	void aRedirectIsNeverFollowed() {
		server.respond(302, "").header("Location", "http://127.0.0.1:1/steal");
		ProviderException e = failure();
		assertThat(e.getKind()).isEqualTo(Kind.BAD_RESPONSE);
		assertThat(server.requests()).isEqualTo(1);
	}

	@Test
	void aNonJsonAnswerIsRejected() {
		server.respond(200, "<html>hi</html>").contentType("text/html");
		assertThat(failure().getKind()).isEqualTo(Kind.BAD_RESPONSE);
	}

	@Test
	void anOversizedAnswerIsCutOff() {
		server.respond(200, "{\"a\":\"" + "x".repeat(5000) + "\"}");
		assertThat(failure().getKind()).isEqualTo(Kind.BAD_RESPONSE);
	}

	@Test
	void aSlowProviderTimesOut() {
		server.respond(200, "{}").delay(1500);
		ProviderException e = catchProviderException(() -> http(Duration.ofMillis(300), 1024).postJson("{}", Map.of()));
		assertThat(e.getKind()).isEqualTo(Kind.TIMEOUT);
	}

	@Test
	void aServerThatSendsHeadersAndThenStallsIsCutOffAtTheTotalTimeLimit() {
		server.stallBodyFor(5000);
		long start = System.nanoTime();

		ProviderException e = catchProviderException(() -> http(Duration.ofMillis(500), 1024).postJson("{}", Map.of()));

		assertThat(e.getKind()).isEqualTo(Kind.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void anUnreachableProviderIsUnavailable() {
		server.close();
		assertThat(failure().getKind()).isEqualTo(Kind.UNAVAILABLE);
	}

	@Test
	void errorsNeverContainTheKeyTheRequestOrTheProviderBody() {
		for (int status : new int[] { 400, 401, 429, 500, 504 }) {
			server.respond(status, "{\"echo\":\"secret-article text " + KEY + "\"}").header("retry-after", "3");
			ProviderException e = failure();
			assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("secret-article").doesNotContain("echo");
		}
	}

	@Test
	void onlyHttpsOrLocalAddressesAreAllowed() {
		assertThatThrownBy(() -> ProviderHttp.Settings.of("http://api.example.com/v1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProviderHttp.Settings.of("ftp://api.example.com/v1")).isInstanceOf(IllegalArgumentException.class);
		assertThat(ProviderHttp.Settings.of("https://api.example.com/v1").endpoint().getHost()).isEqualTo("api.example.com");
		assertThat(ProviderHttp.Settings.of("http://localhost:8080/v1").endpoint().getPort()).isEqualTo(8080);
	}

}
