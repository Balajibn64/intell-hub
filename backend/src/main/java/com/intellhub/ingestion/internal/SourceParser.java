package com.intellhub.ingestion.internal;

import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.util.Set;

/**
 * Turns what one kind of source returned into plain entries. Each source type has exactly one parser; the rest of
 * ingestion (fetching, cleaning, storing, health) is the same for all of them. Whatever a parser returns is still
 * untrusted: it is cleaned and bounded afterwards.
 */
interface SourceParser {

	/** The source types this parser reads. */
	Set<SourceType> types();

	/** @throws FeedParseException if the bytes are not something this parser is willing to read */
	ParsedFeed parse(SourceInfo source, byte[] body, ParseLimits limits) throws FeedParseException;

}
