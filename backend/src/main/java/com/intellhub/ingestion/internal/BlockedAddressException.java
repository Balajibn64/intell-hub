package com.intellhub.ingestion.internal;

import java.net.UnknownHostException;

/** A host resolved to an address we must not connect to. Carries no host or address text. */
class BlockedAddressException extends UnknownHostException {

	BlockedAddressException() {
		super("Blocked address");
	}

}
