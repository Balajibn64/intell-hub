package com.intellhub.ingestion.internal;

/**
 * Turns feed bytes into plain data. The only implementation uses Rome, which is in reduced maintenance,
 * so everything else depends on this interface and no Rome type leaks out.
 */
interface FeedParser {

	/** @throws FeedParseException if the bytes are not a feed we are willing to read */
	ParsedFeed parse(byte[] xml, ParseLimits limits) throws FeedParseException;

}
