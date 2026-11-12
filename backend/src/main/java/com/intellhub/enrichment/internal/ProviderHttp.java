package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.ProviderException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
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
		long deadline = System.nanoTime() + settings.totalTimeout().toNanos();
		try {
			HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				int status = response.statusCode();
				if (status == 429) {
					Duration wait = retryAfter(response.headers().firstValue("retry-after").orElse(null));
					if (wait == null) {
						try {
							wait = retryDelayFrom(readCapped(in, deadline, 8 * 1024));
						}
						catch (ProviderException | IOException ignored) {
							// A missing hint must not hide the fact that we were rate limited.
						}
					}
					throw new ProviderException(Kind.RATE_LIMITED, "The provider is rate limiting us", wait);
				}
				if (status == 408 || status == 504) {
					throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
				}
				if (status >= 500) {
					throw new ProviderException(Kind.UNAVAILABLE, "The provider is unavailable");
				}
				if (status < 200 || status >= 300) {
					// 3xx (never followed), 400, 401, 403 and the rest: our request or key is wrong, retrying will not help.
					throw new ProviderException(Kind.BAD_RESPONSE, "The provider refused the request (HTTP " + status + ")");
				}
				String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
				if (!type.contains("json")) {
					throw new ProviderException(Kind.BAD_RESPONSE, "The provider did not answer with JSON");
				}
				return readCapped(in, deadline, settings.maxResponseBytes());
			}
		}
		catch (HttpTimeoutException e) {
			throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProviderException(Kind.UNAVAILABLE, "Interrupted while waiting for the provider");
		}
		catch (IOException e) {
			throw new ProviderException(Kind.UNAVAILABLE, "Could not reach the provider");
		}
	}

	private static String readCapped(InputStream in, long deadline, int limit) throws IOException, ProviderException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (System.nanoTime() - deadline > 0) {
				throw new ProviderException(Kind.TIMEOUT, "The provider timed out");
			}
			if (out.size() + read > limit) {
				throw new ProviderException(Kind.BAD_RESPONSE, "The provider's answer was too large");
			}
			out.write(buffer, 0, read);
		}
		return out.toString(StandardCharsets.UTF_8);
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
