package com.intellhub.shared;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Decides whether an IP address is a public, routable internet address. Used both when a source URL
 * is accepted and, more importantly, at fetch time on the addresses a hostname really resolves to.
 *
 * <p>Anything that is loopback, private, link-local (including cloud metadata), carrier-grade NAT,
 * multicast, reserved or otherwise internal is not public. For IPv6 only global unicast
 * ({@code 2000::/3}) is allowed, minus 6to4, Teredo and documentation ranges, which also rules out
 * every form that embeds an IPv4 address (NAT64, IPv4-compatible).
 */
public final class PublicAddressPolicy {

	private PublicAddressPolicy() {
	}

	public static boolean isPublic(InetAddress address) {
		if (address instanceof Inet4Address v4) {
			byte[] b = v4.getAddress();
			return !isNonPublicIpv4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
		}
		if (address instanceof Inet6Address v6) {
			return isPublicIpv6(v6.getAddress());
		}
		return false;
	}

	static boolean isNonPublicIpv4(int a, int b, int c) {
		return a == 0 // this network
				|| a == 10
				|| (a == 100 && b >= 64 && b <= 127) // carrier-grade NAT
				|| a == 127
				|| (a == 169 && b == 254) // link-local, cloud metadata
				|| (a == 172 && b >= 16 && b <= 31)
				|| (a == 192 && b == 0 && c == 0)
				|| (a == 192 && b == 168)
				|| (a == 198 && (b == 18 || b == 19))
				|| a >= 224; // multicast, reserved, broadcast
	}

	private static boolean isPublicIpv6(byte[] bytes) {
		int b0 = bytes[0] & 0xff;
		int b1 = bytes[1] & 0xff;
		int b2 = bytes[2] & 0xff;
		int b3 = bytes[3] & 0xff;
		boolean globalUnicast = (b0 & 0xe0) == 0x20;
		boolean sixToFour = b0 == 0x20 && b1 == 0x02;
		boolean teredo = b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00;
		boolean documentation = b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8;
		return globalUnicast && !sixToFour && !teredo && !documentation;
	}

}
