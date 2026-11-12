package com.intellhub.retention;

/** A purge is already running. */
public class RetentionRunningException extends RuntimeException {

	public RetentionRunningException() {
		super("A purge is already running", null, false, false);
	}

}
