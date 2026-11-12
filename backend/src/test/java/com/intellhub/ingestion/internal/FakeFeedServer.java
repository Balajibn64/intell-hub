package com.intellhub.ingestion.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/** A tiny HTTP server on the loopback interface for tests. Tests never touch the real network. */
final class FakeFeedServer implements AutoCloseable {

	record Seen(String path, Map<String, List<String>> headers) {

		boolean hasHeader(String name) {
			return headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase(name));
		}

		String header(String name) {
			return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
					.map(e -> String.join(",", e.getValue())).orElse(null);
		}

	}

	@FunctionalInterface
	interface Handler {

		void handle(HttpExchange exchange) throws IOException;

	}

	private final HttpServer server;

	private final List<Seen> seen = new CopyOnWriteArrayList<>();

	FakeFeedServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
	}

	int port() {
		return server.getAddress().getPort();
	}

	FakeFeedServer on(String path, Handler handler) {
		server.createContext(path, exchange -> {
			seen.add(new Seen(exchange.getRequestURI().getPath(), new java.util.HashMap<>(exchange.getRequestHeaders())));
			try {
				handler.handle(exchange);
			}
			catch (IOException e) {
				// the client went away, which is expected for the abusive handlers
			}
			finally {
				exchange.close();
			}
		});
		return this;
	}

	List<Seen> requests() {
		return new ArrayList<>(seen);
	}

	int requestCount(String path) {
		return (int) seen.stream().filter(s -> s.path().equals(path)).count();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	// ---- handlers ----

	static Handler body(int status, String contentType, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return exchange -> {
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			}
		};
	}

	static Handler redirect(int status, String location) {
		return exchange -> {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(status, -1);
		};
	}

	static Handler redirectWithCookie(int status, String location) {
		return exchange -> {
			exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; Path=/");
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(status, -1);
		};
	}

	static Handler sleepThenBody(long millis, String content) {
		return exchange -> {
			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			body(200, "application/xml", content).handle(exchange);
		};
	}

	/** Never stops sending (chunked), until the client hangs up. */
	static Handler endless() {
		return exchange -> {
			exchange.sendResponseHeaders(200, 0);
			byte[] block = new byte[8192];
			java.util.Arrays.fill(block, (byte) 'x');
			OutputStream out = exchange.getResponseBody();
			while (true) {
				out.write(block);
				out.flush();
			}
		};
	}

	/** A small gzip body that expands to about 50 MB. */
	static Handler gzipBomb() {
		return exchange -> {
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.getResponseHeaders().add("Content-Type", "application/xml");
			exchange.sendResponseHeaders(200, 0);
			try (GZIPOutputStream gzip = new GZIPOutputStream(exchange.getResponseBody())) {
				byte[] zeros = new byte[65536];
				for (int i = 0; i < 800; i++) {
					gzip.write(zeros);
				}
			}
		};
	}

	/** Sends one byte every {@code millis} milliseconds, so no single read ever times out. */
	static Handler trickle(long millis, int bytes) {
		return exchange -> {
			exchange.sendResponseHeaders(200, 0);
			OutputStream out = exchange.getResponseBody();
			for (int i = 0; i < bytes; i++) {
				out.write('x');
				out.flush();
				try {
					Thread.sleep(millis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		};
	}

}
