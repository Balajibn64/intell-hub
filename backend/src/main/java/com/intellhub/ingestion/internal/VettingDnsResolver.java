package com.intellhub.ingestion.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The only place a hostname is turned into addresses for a fetch.
 *
 * <p>HttpClient asks this resolver once per connection and connects to what it returns, so the
 * addresses we vet are exactly the addresses used: there is no second lookup an attacker could answer
 * differently (DNS rebinding), and the lookup itself has a deadline. If any returned address is not allowed, the whole lookup is refused,
 * so a hostname with one public and one private record cannot be used either.
 */
final class VettingDnsResolver implements DnsResolver {

	/** Lookups run here so a stalled DNS server cannot hold a fetch past its deadline. */
	private static final ExecutorService LOOKUPS = Executors.newVirtualThreadPerTaskExecutor();

	private final DnsResolver delegate;

	private final Predicate<InetAddress> allowed;

	private final Duration lookupTimeout;

	VettingDnsResolver(DnsResolver delegate, Predicate<InetAddress> allowed, Duration lookupTimeout) {
		this.delegate = delegate;
		this.allowed = allowed;
		this.lookupTimeout = lookupTimeout;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] addresses = lookup(host);
		if (addresses == null || addresses.length == 0) {
			throw new UnknownHostException("No address");
		}
		for (InetAddress address : addresses) {
			if (!allowed.test(address)) {
				throw new BlockedAddressException();
			}
		}
		return addresses;
	}

	/**
	 * The operating system's lookup cannot be interrupted, so it runs on another thread and is abandoned
	 * after the timeout. The abandoned lookup finishes or fails on its own and its result is discarded.
	 */
	private InetAddress[] lookup(String host) throws UnknownHostException {
		Future<InetAddress[]> future = LOOKUPS.submit(() -> delegate.resolve(host));
		try {
			return future.get(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			future.cancel(true);
			throw new DnsTimeoutException();
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof UnknownHostException unknown) {
				throw unknown;
			}
			throw new UnknownHostException("Lookup failed");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UnknownHostException("Lookup interrupted");
		}
	}

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		return delegate.resolveCanonicalHostname(host);
	}

}
