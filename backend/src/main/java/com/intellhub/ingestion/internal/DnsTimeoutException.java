package com.intellhub.ingestion.internal;

import java.net.UnknownHostException;

/** A name lookup took too long. Carries no host text. */
class DnsTimeoutException extends UnknownHostException {

	DnsTimeoutException() {
		super("DNS lookup timed out");
	}

}
