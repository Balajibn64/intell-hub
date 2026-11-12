package com.intellhub.ingestion.internal;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndPerson;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses RSS and Atom with Rome.
 *
 * <p>DOCTYPE declarations are refused. That blocks external entities (reading local files or making
 * requests during parsing) and entity-expansion bombs. A few very old feed formats need a DOCTYPE
 * and are therefore not supported, which is an accepted trade-off.
 */
@Component
class RomeFeedParser implements FeedParser {

	static final int MAX_TITLE_CHARS = 1000;

	static final int MAX_AUTHOR_CHARS = 500;

	static final int MAX_CONTENT_CHARS = 200_000;

	@Override
	public ParsedFeed parse(byte[] xml, ParseLimits limits) throws FeedParseException {
		if (xml == null || xml.length == 0) {
			throw new FeedParseException();
		}
		SyndFeed feed;
		try {
			SyndFeedInput input = new SyndFeedInput();
			input.setAllowDoctypes(false);
			input.setPreserveWireFeed(false);
			feed = input.build(new XmlReader(new ByteArrayInputStream(xml), true));
		}
		catch (IOException | RuntimeException | com.rometools.rome.io.FeedException e) {
			// Never pass on the document's own text: it is untrusted and may be huge.
			throw new FeedParseException();
		}
		List<ParsedEntry> entries = new ArrayList<>();
		int skippedInvalid = 0;
		int skippedOld = 0;
		for (SyndEntry entry : feed.getEntries()) {
			if (entries.size() >= limits.maxEntries()) {
				break;
			}
			ParsedEntry parsed = toEntry(entry);
			if (parsed == null) {
				skippedInvalid++;
			}
			else if (parsed.publishedAt() != null && parsed.publishedAt().isBefore(limits.notBefore())) {
				skippedOld++;
			}
			else {
				entries.add(parsed);
			}
		}
		return new ParsedFeed(List.copyOf(entries), skippedInvalid, skippedOld);
	}

	private static ParsedEntry toEntry(SyndEntry entry) {
		String link = firstUsableLink(entry);
		String title = bounded(entry.getTitle(), MAX_TITLE_CHARS);
		if (link == null || title == null || title.isBlank()) {
			return null;
		}
		return new ParsedEntry(link, title, author(entry), instant(entry), content(entry));
	}

	private static String firstUsableLink(SyndEntry entry) {
		for (String candidate : new String[] { entry.getLink(), entry.getUri() }) {
			if (candidate != null) {
				String trimmed = candidate.strip();
				String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
				if ((lower.startsWith("http://") || lower.startsWith("https://")) && trimmed.length() <= 2048) {
					return trimmed;
				}
			}
		}
		return null;
	}

	private static String author(SyndEntry entry) {
		String author = entry.getAuthor();
		if ((author == null || author.isBlank()) && entry.getAuthors() != null) {
			for (SyndPerson person : entry.getAuthors()) {
				if (person.getName() != null && !person.getName().isBlank()) {
					author = person.getName();
					break;
				}
			}
		}
		return author == null || author.isBlank() ? null : bounded(author, MAX_AUTHOR_CHARS);
	}

	private static Instant instant(SyndEntry entry) {
		Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
		return date == null ? null : date.toInstant();
	}

	private static String content(SyndEntry entry) {
		String value = null;
		if (entry.getContents() != null) {
			for (SyndContent content : entry.getContents()) {
				if (content.getValue() != null && !content.getValue().isBlank()) {
					value = content.getValue();
					break;
				}
			}
		}
		if (value == null && entry.getDescription() != null) {
			value = entry.getDescription().getValue();
		}
		String bounded = bounded(value, MAX_CONTENT_CHARS);
		return bounded == null ? "" : bounded;
	}

	/** Cuts to a length without splitting a surrogate pair. Null stays null. */
	private static String bounded(String value, int max) {
		if (value == null) {
			return null;
		}
		if (value.length() <= max) {
			return value;
		}
		int end = max;
		if (Character.isHighSurrogate(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end);
	}

}
