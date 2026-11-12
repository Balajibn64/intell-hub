package com.intellhub.ingestion.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The only place a hostname is turned into addresses for a fetch.
 *
 * <p>HttpClient asks this resolver once per connection and connects to what it returns, so the
 * addresses we vet are exactly the addresses used: there is no second lookup an attacker could answer
 * differently (DNS rebinding). If any returned address is not allowed, the whole lookup is refused,
 * so a hostname with one public and one private record cannot be used either.
 */
final class VettingDnsResolver implements DnsResolver {

	private final DnsResolver delegate;

	private final Predicate<InetAddress> allowed;

	VettingDnsResolver(DnsResolver delegate, Predicate<InetAddress> allowed) {
		this.delegate = delegate;
		this.allowed = allowed;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] addresses = delegate.resolve(host);
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

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		return delegate.resolveCanonicalHostname(host);
	}

}
