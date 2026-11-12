package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that talks to a provider's web API. It posts a JSON body to one fixed address and turns
 * every outcome into either the response text or a {@link ProviderException}.
 *
 * <ul>
 * <li>The address must be https (plain http only for the local machine, which the tests use), so an API key
 * is never sent in the clear. Redirects are never followed, so the key cannot be sent anywhere else.</li>
 * <li>Connect, response and total time are bounded, and so is the size of the reply.</li>
 * <li>Errors are fixed texts. The key, the request and whatever the server said never reach an exception,
 * because a provider's error body can echo article text.</li>
 * </ul>
 */
final class ProviderHttp {

	static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1);

	static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

	private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d{1,6}(?:\\.\\d{1,3})?)s\"");

	record Settings(URI endpoint, Duration connectTimeout, Duration totalTimeout, int maxResponseBytes) {

		Settings {
			String scheme = endpoint.getScheme();
			String host = endpoint.getHost();
			boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
			if (!("https".equals(scheme) || ("http".equals(scheme) && local))) {
				throw new IllegalArgumentException("A provider address must be https");
			}
		}

		static Settings of(String endpoint) {
			return new Settings(URI.create(endpoint), Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
		}
	}

	private final Settings settings;

	private final HttpClient client;

	ProviderHttp(Settings settings) {
		this.settings = settings;
		this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	/** Posts the body and returns the reply text of a successful (2xx) answer. */
	String postJson(String body, Map<String, String> headers) throws ProviderException {
		HttpRequest.Builder request = HttpRequest.newBuilder(settings.endpoint()).timeout(settings.totalTimeout())
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		headers.forEach(request::header);
		// Sending and reading the (capped) reply both happen in one task whose total wait is bounded here, and the
		// stream is closed on a timeout, so a server that sends headers and then stalls cannot hold us.
		AtomicReference<InputStream> open = new AtomicReference<>();
		CompletableFuture<Reply> pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
				.thenApply(response -> {
					open.set(response.body());
					try (InputStream in = response.body()) {
						return new Reply(response.statusCode(), response.headers(),
								in.readNBytes(settings.maxResponseBytes() + 1));
					}
					catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
		try {
			return interpret(pending.get(settings.totalTimeout().toMillis(), TimeUnit.MILLISECONDS));
		}
		catch (TimeoutException e) {
			abandon(pending, open);
			throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
		}
		catch (InterruptedException e) {
			abandon(pending, open);
			Thread.currentThread().interrupt();
			throw new ProviderException(Kind.UNAVAILABLE, "Interrupted while waiting for the provider");
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof HttpTimeoutException) {
				throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
			}
			throw new ProviderException(Kind.UNAVAILABLE, "Could not reach the provider");
		}
	}

	private record Reply(int status, HttpHeaders headers, byte[] body) {
	}

	private static void abandon(CompletableFuture<Reply> pending, AtomicReference<InputStream> open) {
		pending.cancel(true);
		InputStream in = open.get();
		if (in != null) {
			try {
				in.close();
			}
			catch (IOException ignored) {
				// nothing more to do: the call is being abandoned
			}
		}
	}

	private String interpret(Reply response) throws ProviderException {
		int status = response.status();
		byte[] bytes = response.body();
		if (status == 429) {
			Duration wait = retryAfter(response.headers().firstValue("retry-after").orElse(null));
			if (wait == null && bytes != null) {
				wait = retryDelayFrom(new String(bytes, 0, Math.min(bytes.length, 8 * 1024), StandardCharsets.UTF_8));
			}
			throw new ProviderException(Kind.RATE_LIMITED, "The provider is rate limiting us", wait);
		}
		if (status == 408 || status == 504) {
			throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
		}
		if (status == 401 || status == 403) {
			// Our key is wrong or revoked: not the article's fault, so it must not use up an article's attempts.
			throw new ProviderException(Kind.UNAVAILABLE, "The provider rejected our credentials");
		}
		if (status >= 500) {
			throw new ProviderException(Kind.UNAVAILABLE, "The provider is unavailable");
		}
		if (status < 200 || status >= 300) {
			// 3xx (never followed), 400 and the rest: the request was refused, retrying it unchanged will not help.
			throw new ProviderException(Kind.BAD_RESPONSE, "The provider refused the request (HTTP " + status + ")");
		}
		String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
		if (!type.contains("json")) {
			throw new ProviderException(Kind.BAD_RESPONSE, "The provider did not answer with JSON");
		}
		if (bytes == null || bytes.length > settings.maxResponseBytes()) {
			throw new ProviderException(Kind.BAD_RESPONSE, "The provider's answer was too large");
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** The retry-after header in seconds, kept between one second and one hour; null if absent or not a number. */
	static Duration retryAfter(String header) {
		if (header == null) {
			return null;
		}
		try {
			return clamp(Duration.ofMillis((long) (Double.parseDouble(header.strip()) * 1000)));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/** Gemini states the wait in the error body as {@code "retryDelay": "3.5s"}. */
	private static Duration retryDelayFrom(String body) {
		Matcher m = RETRY_DELAY.matcher(body);
		return m.find() ? retryAfter(m.group(1)) : null;
	}

	private static Duration clamp(Duration d) {
		if (d.compareTo(MIN_RETRY_AFTER) < 0) {
			return MIN_RETRY_AFTER;
		}
		return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
	}

}
