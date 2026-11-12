package com.intellhub.ingestion.internal;

import com.intellhub.shared.Text;
import com.intellhub.sources.SourceInfo;
import com.intellhub.sources.SourceType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Hugging Face's daily papers list: a JSON array of
 * {@code {"paper":{id, title, summary, authors:[{name}], publishedAt, upvotes}, "title", "summary", "publishedAt"}}.
 *
 * <p>The link is built from the paper id, which must look like an arXiv id, never taken from the document. The
 * summary is plain text and is escaped before it is treated as HTML. Every field is type-checked; a document
 * that is not an array fails with a fixed message.
 */
@Component
class HuggingFacePapersParser implements SourceParser {

	/** Most papers looked at, whatever the document claims to hold. */
	static final int MAX_PAPERS_READ = 200;

	private static final int MAX_AUTHORS = 10;

	private static final Pattern ARXIV_ID = Pattern.compile("^\\d{4}\\.\\d{4,6}(v\\d{1,2})?$");

	private final ObjectMapper mapper;

	HuggingFacePapersParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public Set<SourceType> types() {
		return Set.of(SourceType.HUGGINGFACE);
	}

	@Override
	public ParsedFeed parse(SourceInfo source, byte[] body, ParseLimits limits) throws FeedParseException {
		JsonNode root;
		try {
			root = mapper.readTree(body);
		}
		catch (RuntimeException e) { // includes Jackson's own exceptions
			throw new FeedParseException(); // never pass on the document's own text
		}
		if (root == null || !root.isArray()) {
			throw new FeedParseException();
		}
		List<ParsedEntry> entries = new ArrayList<>();
		int skippedInvalid = 0;
		int skippedOld = 0;
		int read = 0;
		for (JsonNode item : root) {
			if (entries.size() >= limits.maxEntries() || ++read > MAX_PAPERS_READ) {
				break;
			}
			ParsedEntry entry = item != null && item.isObject() ? toEntry(item) : null;
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

	private static ParsedEntry toEntry(JsonNode item) {
		JsonNode paper = item.get("paper");
		if (paper == null || !paper.isObject()) {
			return null;
		}
		String id = JsonFields.text(paper, "id", 30);
		String title = firstText(item, paper, "title", RomeFeedParser.MAX_TITLE_CHARS);
		if (id == null || !ARXIV_ID.matcher(id).matches() || title == null) {
			return null;
		}
		String summary = firstText(paper, item, "summary", RomeFeedParser.MAX_CONTENT_CHARS);
		Long upvotes = JsonFields.whole(paper, "upvotes");
		String html = (summary == null ? "" : "<p>" + HtmlUtils.htmlEscape(summary) + "</p>")
				+ (upvotes == null || upvotes < 0 ? "" : "<p>" + upvotes + " upvotes on Hugging Face</p>");
		return new ParsedEntry("https://huggingface.co/papers/" + id, title, authors(paper), published(item, paper), html);
	}

	/** The first of the two nodes that has the text field. */
	private static String firstText(JsonNode first, JsonNode second, String name, int max) {
		String value = JsonFields.text(first, name, max);
		return value != null ? value : JsonFields.text(second, name, max);
	}

	private static String authors(JsonNode paper) {
		JsonNode list = paper.get("authors");
		if (list == null || !list.isArray()) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (JsonNode author : list) {
			if (names.size() >= MAX_AUTHORS) {
				break;
			}
			String name = author != null && author.isObject() ? JsonFields.text(author, "name", 100) : null;
			if (name != null) {
				names.add(name);
			}
		}
		return names.isEmpty() ? null : Text.cut(String.join(", ", names), RomeFeedParser.MAX_AUTHOR_CHARS);
	}

	private static Instant published(JsonNode item, JsonNode paper) {
		for (String text : new String[] { JsonFields.text(item, "publishedAt", 40), JsonFields.text(paper, "publishedAt", 40) }) {
			if (text != null) {
				try {
					return Instant.parse(text);
				}
				catch (DateTimeParseException e) {
					// try the next one, then give up on a date
				}
			}
		}
		return null;
	}

}
