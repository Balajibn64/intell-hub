package com.intellhub.ingestion.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;

/**
 * Fetches a URL on behalf of the server while treating the URL as hostile.
 *
 * <ul>
 * <li>Every URL, including each redirect target, is checked as text, and every hostname is resolved
 * through {@link VettingDnsResolver}, so only public addresses are ever connected to.</li>
 * <li>Redirects are followed by hand: a few at most, http and https only, never from https to http.</li>
 * <li>No proxy, cookies, credentials or connection reuse; TLS certificates and host names are verified.</li>
 * <li>The decoded body is capped (so a compression bomb is cut off), and a hard deadline covers the
 * whole fetch (so a slowly trickling server is cut off).</li>
 * <li>Failures are fixed reasons. Nothing from the remote server is ever put in an error.</li>
 * </ul>
 */
final class SafeHttpFetcher implements AutoCloseable {

	private static final String ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, "
			+ "text/xml;q=0.8, */*;q=0.5";

	private final FetchSettings settings;

	private final Function<String, String> urlCheck;

	private final CloseableHttpClient client;

	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "fetch-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * @param urlCheck returns a problem description for a URL that must not be fetched, or null; in production this
	 * is the shared public-URL policy
	 * @param addressAllowed decides whether a resolved address may be connected to; in production the shared
	 * public-address policy
	 * @param baseResolver does the actual name lookup; in production the system resolver
	 */
	SafeHttpFetcher(FetchSettings settings, Function<String, String> urlCheck, Predicate<InetAddress> addressAllowed,
			DnsResolver baseResolver) {
		this.settings = settings;
		this.urlCheck = urlCheck;
		this.client = HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.setDnsResolver(new VettingDnsResolver(baseResolver, addressAllowed, settings.connectTimeout()))
						.setDefaultConnectionConfig(ConnectionConfig.custom()
								.setConnectTimeout(Timeout.of(settings.connectTimeout()))
								.setSocketTimeout(Timeout.of(settings.responseTimeout()))
								.build())
						.build())
				.setDefaultRequestConfig(RequestConfig.custom()
						.setResponseTimeout(Timeout.of(settings.responseTimeout()))
						.setConnectionRequestTimeout(Timeout.of(settings.connectTimeout()))
						.setRedirectsEnabled(false)
						.build())
				.setUserAgent(settings.userAgent())
				.disableRedirectHandling()
				.disableCookieManagement()
				.disableAuthCaching()
				.disableAutomaticRetries()
				.setConnectionReuseStrategy((request, response, context) -> false)
				.build();
	}

	FetchOutcome fetch(String startUrl) {
		long deadline = System.nanoTime() + settings.totalTimeout().toNanos();
		String current = startUrl;
		for (int redirects = 0;; redirects++) {
			if (urlCheck.apply(current) != null) {
				return failure(FetchOutcome.Reason.INVALID_URL);
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				return failure(FetchOutcome.Reason.TIMEOUT);
			}
			Hop hop = executeOnce(URI.create(current), remaining);
			if (hop.outcome() != null) {
				return hop.outcome();
			}
			if (hop.redirectTo() == null) {
				return new FetchOutcome.Success(hop.body(), current, hop.contentType());
			}
			if (redirects >= settings.maxRedirects()) {
				return failure(FetchOutcome.Reason.TOO_MANY_REDIRECTS);
			}
			URI next;
			try {
				next = URI.create(current).resolve(hop.redirectTo());
			}
			catch (IllegalArgumentException e) {
				return failure(FetchOutcome.Reason.INVALID_URL);
			}
			if (isDowngrade(URI.create(current), next)) {
				return failure(FetchOutcome.Reason.INVALID_URL);
			}
			current = next.toString();
		}
	}

	/** True when a redirect would move from https to anything that is not https. */
	static boolean isDowngrade(URI from, URI to) {
		return "https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(to.getScheme());
	}

	private Hop executeOnce(URI uri, long remainingNanos) {
		HttpGet request = new HttpGet(uri);
		request.setHeader(HttpHeaders.ACCEPT, ACCEPT);
		AtomicBoolean timedOut = new AtomicBoolean();
		ScheduledFuture<?> alarm = watchdog.schedule(() -> {
			timedOut.set(true);
			request.abort();
		}, remainingNanos, TimeUnit.NANOSECONDS);
		try {
			return client.execute(request, response -> handle(response, request));
		}
		catch (TooLargeException e) {
			return Hop.failed(failure(FetchOutcome.Reason.TOO_LARGE));
		}
		catch (IOException | RuntimeException e) {
			if (timedOut.get()) {
				return Hop.failed(failure(FetchOutcome.Reason.TIMEOUT));
			}
			if (causedBy(e, DnsTimeoutException.class)) {
				return Hop.failed(failure(FetchOutcome.Reason.TIMEOUT));
			}
			if (causedBy(e, BlockedAddressException.class)) {
				return Hop.failed(failure(FetchOutcome.Reason.BLOCKED_ADDRESS));
			}
			if (causedBy(e, TooLargeException.class)) {
				return Hop.failed(failure(FetchOutcome.Reason.TOO_LARGE));
			}
			if (causedBy(e, SocketTimeoutException.class) || causedBy(e, ConnectTimeoutException.class)) {
				return Hop.failed(failure(FetchOutcome.Reason.TIMEOUT));
			}
			return Hop.failed(failure(FetchOutcome.Reason.NETWORK_ERROR));
		}
		finally {
			alarm.cancel(false);
		}
	}

	private Hop handle(ClassicHttpResponse response, HttpGet request) throws IOException {
		int status = response.getCode();
		if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
			Header location = response.getFirstHeader(HttpHeaders.LOCATION);
			if (location == null || location.getValue() == null || location.getValue().isBlank()) {
				return Hop.failed(failure(FetchOutcome.Reason.INVALID_URL));
			}
			return Hop.redirect(location.getValue().strip());
		}
		if (status < 200 || status > 299) {
			return Hop.failed(new FetchOutcome.Failure(FetchOutcome.Reason.HTTP_ERROR, status));
		}
		HttpEntity entity = response.getEntity();
		byte[] body = readBounded(entity, request);
		String contentType = entity == null || entity.getContentType() == null ? null
				: entity.getContentType().toLowerCase(Locale.ROOT);
		return Hop.body(body, contentType);
	}

	private byte[] readBounded(HttpEntity entity, HttpGet request) throws IOException {
		if (entity == null) {
			return new byte[0];
		}
		long max = settings.maxBodyBytes();
		if (entity.getContentLength() > max) {
			request.abort();
			throw new TooLargeException();
		}
		try (InputStream in = entity.getContent()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(max, 64 * 1024));
			byte[] buffer = new byte[8192];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > max) {
					// Counted on decoded bytes, and the Content-Length header is never trusted. Abort at once:
					// otherwise the client would try to drain the rest of an endless body before giving up.
					request.abort();
					throw new TooLargeException();
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 10; depth++) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static FetchOutcome.Failure failure(FetchOutcome.Reason reason) {
		return new FetchOutcome.Failure(reason, 0);
	}

	@Override
	public void close() {
		watchdog.shutdownNow();
		try {
			client.close();
		}
		catch (IOException e) {
			// nothing useful to do while shutting down
		}
	}

	private static final class TooLargeException extends IOException {

		TooLargeException() {
			super("Response too large");
		}

	}

	/** One round trip: a failure, a redirect target, or a body. */
	private record Hop(FetchOutcome outcome, String redirectTo, byte[] body, String contentType) {

		static Hop failed(FetchOutcome failure) {
			return new Hop(failure, null, null, null);
		}

		static Hop redirect(String location) {
			return new Hop(null, location, null, null);
		}

		static Hop body(byte[] body, String contentType) {
			return new Hop(null, null, body, contentType);
		}

	}

}
