package com.intellhub.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** These take literal addresses only, so nothing here touches DNS. */
class PublicAddressPolicyTests {

	@ParameterizedTest
	@ValueSource(strings = { "93.184.216.34", "8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.255.255", "100.63.255.255",
			"100.128.0.1", "2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2001:4860:4860::8888" })
	void publicAddressesAreAccepted(String literal) throws Exception {
		assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).as(literal).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "127.255.255.254", "0.0.0.0", "10.0.0.1", "172.16.0.1", "172.31.255.255",
			"192.168.0.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "192.0.0.1", "198.18.0.1",
			"224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1",
			"fd12:3456::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",
			"64:ff9b::7f00:1", "2002:7f00:1::", "::127.0.0.1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001:db8::1",
			"100::1" })
	void internalAndSpecialAddressesAreRejected(String literal) throws Exception {
		assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).as(literal).isFalse();
	}

}
