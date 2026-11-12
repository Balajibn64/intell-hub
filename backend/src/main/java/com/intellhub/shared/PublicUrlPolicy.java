package com.intellhub.shared;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level rules for URLs the server may fetch or link to: http and https only, no credentials, and
 * no host that is, or could be read as, a loopback, private, link-local or otherwise internal address.
 *
 * <p>This never resolves DNS, so a public name that points at a private address still passes here.
 * The fetcher therefore also checks the resolved addresses with {@link PublicAddressPolicy}.
 */
public final class PublicUrlPolicy {

	public static final int MAX_LENGTH = 2048;

	private static final Pattern STRICT_IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

	private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

	private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

	private static final String[] INTERNAL_SUFFIXES = { ".localhost", ".local", ".internal", ".localdomain", ".lan",
			".home.arpa" };

	private PublicUrlPolicy() {
	}

	/** Returns why the URL is rejected, or null if it is acceptable. The URL itself is never echoed. */
	public static String problem(String url) {
		if (url == null || url.isEmpty()) {
			return "URL is required";
		}
		if (url.length() > MAX_LENGTH) {
			return "URL is too long";
		}
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (c <= 0x20 || c == 0x7f || c == '\\') {
				return "URL contains whitespace or control characters";
			}
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException e) {
			return "URL is not valid";
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return "URL must start with http:// or https://";
		}
		if (uri.getRawUserInfo() != null) {
			return "URL must not contain credentials";
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			return "URL must have a valid host";
		}
		int port = uri.getPort();
		if (port != -1 && (port < 1 || port > 65535)) {
			return "URL port is out of range";
		}
		return hostProblem(host);
	}

	/**
	 * Brings equivalent URLs to one form: lowercase scheme and host, no trailing dot on the host, and no
	 * port when it is the default for the scheme. The URL must already have passed {@link #problem}.
	 */
	public static String normalize(String url) {
		URI uri = URI.create(url);
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		int port = uri.getPort();
		boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
		String authority = host + (port == -1 || defaultPort ? "" : ":" + port);
		int restStart = url.indexOf("://") + 3 + uri.getRawAuthority().length();
		return scheme + "://" + authority + url.substring(restStart);
	}

	private static String hostProblem(String rawHost) {
		if (rawHost.startsWith("[")) {
			return ipv6Problem(rawHost);
		}
		String host = rawHost.toLowerCase(Locale.ROOT);
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		if (host.isEmpty() || host.length() > 253) {
			return "URL must have a valid host";
		}
		Matcher quad = STRICT_IPV4.matcher(host);
		if (quad.matches()) {
			return ipv4Problem(quad);
		}
		String[] labels = host.split("\\.", -1);
		for (String label : labels) {
			if (!LABEL.matcher(label).matches()) {
				return "URL must have a valid host";
			}
		}
		String last = labels[labels.length - 1];
		if (last.chars().allMatch(Character::isDigit) || last.startsWith("0x")) {
			// Forms like 2130706433, 127.1 or 0x7f.0.0.1 are read as IP addresses by many HTTP clients.
			return "URL host looks like a numeric address";
		}
		if (labels.length < 2 || host.equals("localhost")) {
			return "URL host must be a public domain name";
		}
		for (String suffix : INTERNAL_SUFFIXES) {
			if (host.endsWith(suffix)) {
				return "URL host must be a public domain name";
			}
		}
		return null;
	}

	private static String ipv4Problem(Matcher quad) {
		int[] o = new int[4];
		for (int i = 0; i < 4; i++) {
			String part = quad.group(i + 1);
			if (part.length() > 1 && part.startsWith("0")) {
				return "URL host looks like a numeric address";
			}
			o[i] = Integer.parseInt(part);
			if (o[i] > 255) {
				return "URL must have a valid host";
			}
		}
		return PublicAddressPolicy.isNonPublicIpv4(o[0], o[1], o[2])
				? "URL must not point to a private or internal address" : null;
	}

	private static String ipv6Problem(String bracketed) {
		String inner = bracketed.substring(1, bracketed.length() - 1);
		if (inner.isEmpty() || !inner.contains(":") || !IPV6_CHARS.matcher(inner).matches()) {
			return "URL must have a valid host";
		}
		InetAddress address;
		try {
			// Only reached for literals (they contain ':' and only hex digits, colons and dots), so no DNS lookup.
			address = InetAddress.getByName(inner);
		}
		catch (UnknownHostException e) {
			return "URL must have a valid host";
		}
		// An IPv4-mapped address such as ::ffff:10.0.0.1 comes back as plain IPv4 and is judged as IPv4.
		return PublicAddressPolicy.isPublic(address) ? null : "URL must not point to a private or internal address";
	}

}
