package com.intellhub.enrichment.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A local stand-in for a provider's API, so tests never touch the real network. */
final class StubProviderServer implements AutoCloseable {

	private final HttpServer server;

	private volatile int status = 200;

	private volatile String contentType = "application/json";

	private volatile String body = "{}";

	private volatile long delayMillis = 0;

	private final Map<String, String> responseHeaders = new LinkedHashMap<>();

	private volatile String lastPath;

	private volatile String lastBody;

	private volatile Map<String, String> lastHeaders = Map.of();

	private final AtomicInteger requests = new AtomicInteger();

	StubProviderServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			lastPath = exchange.getRequestURI().toString();
			lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Map<String, String> headers = new LinkedHashMap<>();
			exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
			lastHeaders = headers;
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
	}

	String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	StubProviderServer respond(int status, String body) {
		this.status = status;
		this.body = body;
		return this;
	}

	StubProviderServer contentType(String contentType) {
		this.contentType = contentType;
		return this;
	}

	StubProviderServer header(String name, String value) {
		responseHeaders.put(name, value);
		return this;
	}

	StubProviderServer delay(long millis) {
		this.delayMillis = millis;
		return this;
	}

	String lastPath() {
		return lastPath;
	}

	String lastBody() {
		return lastBody;
	}

	Map<String, String> lastHeaders() {
		return lastHeaders;
	}

	int requests() {
		return requests.get();
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
