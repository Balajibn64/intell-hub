package com.intellhub.ingestion.internal;

import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Plain RSS and Atom feeds, and the two types that are just feeds at a fixed address: GitHub's releases Atom feed
 * and arXiv's category feeds.
 */
@Component
class RssSourceParser implements SourceParser {

	private final FeedParser feeds;

	RssSourceParser(FeedParser feeds) {
		this.feeds = feeds;
	}

	@Override
	public Set<SourceType> types() {
		return Set.of(SourceType.RSS, SourceType.GITHUB_RELEASES, SourceType.ARXIV);
	}

	@Override
	public ParsedFeed parse(SourceInfo source, byte[] body, ParseLimits limits) throws FeedParseException {
		return feeds.parse(body, limits);
	}

}
