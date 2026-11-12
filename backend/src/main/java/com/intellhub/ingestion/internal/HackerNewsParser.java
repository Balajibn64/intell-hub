package com.intellhub.ingestion.internal;

import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Algolia's Hacker News API (the front page): {@code {"hits":[{objectID, title, url, author, points,
 * num_comments, created_at_i, story_text}]}}.
 *
 * <p>A story links to someone else's page, which is not fetched, so the entry carries the title and a line saying
 * how popular it is. Ask HN posts have no link and use the discussion page. Stories below the source's
 * {@code minPoints} (default 50) are left out. Nothing here is trusted: every field is type-checked, and the
 * only HTML (an Ask HN body) goes through the same sanitizer as any feed content afterwards.
 */
@Component
class HackerNewsParser implements SourceParser {

	static final int DEFAULT_MIN_POINTS = 50;

	/** Most hits looked at, whatever the document claims to hold. */
	static final int MAX_HITS_READ = 200;

	private static final Pattern OBJECT_ID = Pattern.compile("^\\d{1,12}$");

	private final ObjectMapper mapper;

	HackerNewsParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public Set<SourceType> types() {
		return Set.of(SourceType.HN);
	}

	@Override
	public ParsedFeed parse(SourceInfo source, byte[] body, ParseLimits limits) throws FeedParseException {
		JsonNode hits;
		try {
			JsonNode root = mapper.readTree(body);
			hits = root == null ? null : root.get("hits");
		}
		catch (RuntimeException e) { // includes Jackson's own exceptions
			throw new FeedParseException(); // never pass on the document's own text
		}
		if (hits == null || !hits.isArray()) {
			throw new FeedParseException();
		}
		long minPoints = source.config().get("minPoints") instanceof Number n ? n.longValue() : DEFAULT_MIN_POINTS;
		List<ParsedEntry> entries = new ArrayList<>();
		int skippedInvalid = 0;
		int skippedOld = 0;
		int read = 0;
		for (JsonNode hit : hits) {
			if (entries.size() >= limits.maxEntries() || ++read > MAX_HITS_READ) {
				break;
			}
			ParsedEntry entry = hit != null && hit.isObject() ? toEntry(hit, minPoints) : null;
			if (entry == null) {
				skippedInvalid++;
			}
			else if (entry.publishedAt() != null && entry.publishedAt().isBefore(limits.notBefore())) {
				skippedOld++;
			}
			else {
				entries.add(entry);
			}
		}
		return new ParsedFeed(List.copyOf(entries), skippedInvalid, skippedOld);
	}

	private static ParsedEntry toEntry(JsonNode hit, long minPoints) {
		String id = JsonFields.text(hit, "objectID", 20);
		String title = JsonFields.text(hit, "title", RomeFeedParser.MAX_TITLE_CHARS);
		if (id == null || !OBJECT_ID.matcher(id).matches() || title == null) {
			return null;
		}
		Long points = JsonFields.whole(hit, "points");
		if ((points == null ? 0 : points) < minPoints) {
			return null;
		}
		String link = JsonFields.httpLink(JsonFields.text(hit, "url", JsonFields.MAX_LINK_CHARS + 1));
		if (link == null) {
			link = "https://news.ycombinator.com/item?id=" + id; // Ask HN and other posts without an outside link
		}
		Long comments = JsonFields.whole(hit, "num_comments");
		Long created = JsonFields.whole(hit, "created_at_i");
		String stats = (points == null ? 0 : points) + " points, " + (comments == null ? 0 : comments) + " comments on Hacker News";
		String storyText = JsonFields.text(hit, "story_text", RomeFeedParser.MAX_CONTENT_CHARS);
		String html = "<p>" + HtmlUtils.htmlEscape(stats) + "</p>" + (storyText == null ? "" : storyText);
		return new ParsedEntry(link, title, JsonFields.text(hit, "author", RomeFeedParser.MAX_AUTHOR_CHARS),
				created == null || created < 0 || created > 32_503_680_000L ? null : Instant.ofEpochSecond(created), html);
	}

}
