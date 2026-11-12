package com.intellhub.sources.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intellhub.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SourceUrlValidatorTests {

	@ParameterizedTest
	@ValueSource(strings = {
			"https://example.com/feed.xml",
			"HTTPS://Example.COM/Feed",
			"http://example.com:8080/a?b=c#frag",
			"https://sub.domain.co.uk/rss",
			"https://example.com./feed",
			"https://xn--bcher-kva.example/feed",
			"https://93.184.216.34/feed",
			"http://172.32.0.1/feed",
			"https://[2606:4700:4700::1111]/feed",
			"https://[::ffff:93.184.216.34]/feed",
			"https://example.com/%0d%0a",
			"https://example.com:/feed" })
	void acceptsOrdinaryPublicUrls(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(url).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {
			" https://example.com/feed",
			"https://example.com/feed ",
			"https://example.com/a b",
			"https://example.com/\n",
			"https://example.com/\u0000",
			"https://example.com\\@evil.example/",
			"ftp://example.com/feed",
			"file:///etc/passwd",
			"javascript:alert(1)",
			"data:text/plain,hi",
			"gopher://example.com/",
			"//example.com/feed",
			"example.com/feed",
			"https://",
			"https:///path",
			"https://user:pw@example.com/",
			"https://user@example.com/",
			"https://example.com:0/",
			"https://example.com:99999/",
			"https://exa_mple.com/",
			"https://example..com/",
			"https://-bad.example/",
			"https://bad-.example/",
			"https://bücher.example/" })
	void rejectsMalformedOrDisallowedUrls(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(String.valueOf(url)).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://localhost/",
			"http://LOCALHOST/",
			"http://localhost./",
			"http://foo.localhost/",
			"http://printer.local/",
			"http://metadata.google.internal/computeMetadata/v1/",
			"http://nas.lan/",
			"http://router.home.arpa/",
			"http://intranet/",
			"http://metadata/" })
	void rejectsInternalHostNames(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(url).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://127.0.0.1/",
			"http://127.255.255.254/",
			"http://0.0.0.0/",
			"http://10.0.0.5/",
			"http://10.255.255.255/",
			"http://172.16.0.1/",
			"http://172.31.255.255/",
			"http://192.168.1.1/",
			"http://169.254.169.254/latest/meta-data/",
			"http://100.64.0.1/",
			"http://100.127.255.255/",
			"http://192.0.0.8/",
			"http://198.18.0.1/",
			"http://224.0.0.1/",
			"http://240.0.0.1/",
			"http://255.255.255.255/" })
	void rejectsPrivateAndSpecialIpv4Literals(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(url).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://2130706433/",
			"http://127.1/",
			"http://127.0.1/",
			"http://0x7f000001/",
			"http://0x7f.0.0.1/",
			"http://0177.0.0.1/",
			"http://017700000001/",
			"http://010.0.0.1/",
			"http://300.1.1.1/",
			"http://1.2.3.256/" })
	void rejectsAmbiguousNumericHosts(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(url).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://[::1]/",
			"http://[::]/",
			"http://[0:0:0:0:0:0:0:1]/",
			"http://[fe80::1]/",
			"http://[fc00::1]/",
			"http://[fd12:3456:789a::1]/",
			"http://[fec0::1]/",
			"http://[ff02::1]/",
			"http://[::ffff:127.0.0.1]/",
			"http://[::ffff:10.0.0.1]/",
			"http://[::ffff:169.254.169.254]/",
			"http://[not-an-address]/",
			"http://[]/" })
	void rejectsPrivateAndInvalidIpv6Literals(String url) {
		assertThat(SourceUrlValidator.problem(url)).as(url).isNotNull();
	}

	@Test
	void rejectsUrlsOverTheLengthLimit() {
		String longUrl = "https://example.com/" + "a".repeat(SourceUrlValidator.MAX_LENGTH);

		assertThat(SourceUrlValidator.problem(longUrl)).isNotNull();
		assertThat(SourceUrlValidator.problem("https://example.com/" + "a".repeat(100))).isNull();
	}

	@Test
	void theUrlIsNeverEchoedBackInTheMessage() {
		String secretish = "http://user:hunter2@10.0.0.1/";

		assertThat(SourceUrlValidator.problem(secretish)).doesNotContain("hunter2").doesNotContain("10.0.0.1");
	}

	@Test
	void validateThrowsABadRequestApiException() {
		assertThatThrownBy(() -> SourceUrlValidator.validate("http://127.0.0.1/"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus().value()).isEqualTo(400);
					assertThat(e.getCode()).isEqualTo("INVALID_SOURCE_URL");
				});
		SourceUrlValidator.validate("https://example.com/feed");
	}

}
