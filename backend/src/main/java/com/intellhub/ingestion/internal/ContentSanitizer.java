package com.intellhub.ingestion.internal;

import com.intellhub.articles.NewArticle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Turns untrusted feed text into safe values.
 *
 * <p>HTML is reduced to a small allowlist of formatting tags and plain http(s) links that always carry
 * {@code rel="nofollow noopener noreferrer"}. Scripts, styles, frames, forms, images, SVG, event handlers,
 * style and class attributes, and any non-http(s) URL scheme are removed. Titles, authors and excerpts are
 * plain text. The result is meant to be rendered as HTML only for {@code contentSanitized}; everything else
 * must be rendered as text.
 */
@Component
class ContentSanitizer {

	/** Fallback when sanitized HTML is still too long: this much plain text, escaped, fits the limit. */
	private static final int FALLBACK_TEXT_CHARS = 8000;

	private static final String ELLIPSIS = "…";

	private static final Safelist ALLOWED = Safelist.none()
			.addTags("p", "br", "a", "ul", "ol", "li", "strong", "em", "b", "i", "code", "pre", "blockquote", "h1", "h2",
					"h3", "h4", "h5", "h6", "hr")
			.addAttributes("a", "href")
			.addProtocols("a", "href", "http", "https")
			.addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer");

	private static final String NOISE = "script, style, noscript, iframe, object, embed, template, svg, math, form, "
			+ "head, title, link, meta, base";

	private static final String BLOCKS = "p, div, br, li, tr, td, th, h1, h2, h3, h4, h5, h6, blockquote, pre, hr, ul, ol, "
			+ "table, section, article, header, footer";

	/**
	 * @param rawHtml the entry content as given by the feed
	 * @param baseUrl the entry URL, used to turn relative links into absolute ones; unresolvable links are dropped
	 * @return safe HTML of at most {@link NewArticle#MAX_CONTENT} characters, or an empty string
	 */
	String sanitizeHtml(String rawHtml, String baseUrl) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return "";
		}
		Document.OutputSettings output = new Document.OutputSettings().prettyPrint(false);
		String clean = Jsoup.clean(rawHtml, baseUrl == null ? "" : baseUrl, ALLOWED.preserveRelativeLinks(false), output);
		if (clean.length() <= NewArticle.MAX_CONTENT) {
			return clean;
		}
		// Cutting HTML in the middle would leave broken tags, so fall back to a short plain-text paragraph.
		String text = cut(toPlainText(rawHtml), FALLBACK_TEXT_CHARS);
		return "<p>" + Entities.escape(text) + "</p>";
	}

	/** Plain text of some HTML: scripts and styles excluded, blocks separated, entities decoded, spaces collapsed. */
	String toPlainText(String rawHtml) {
		if (rawHtml == null || rawHtml.isBlank()) {
			return "";
		}
		Document doc = Jsoup.parseBodyFragment(rawHtml);
		doc.select(NOISE).remove();
		for (Element block : doc.select(BLOCKS)) {
			block.after(" ");
		}
		return doc.text().strip();
	}

	/** A plain-text title of at most {@link NewArticle#MAX_TITLE} characters. */
	String title(String raw) {
		return cut(toPlainText(raw), NewArticle.MAX_TITLE);
	}

	/** A plain-text author of at most {@link NewArticle#MAX_AUTHOR} characters, or null when empty. */
	String author(String raw) {
		String text = cut(toPlainText(raw), NewArticle.MAX_AUTHOR);
		return text.isEmpty() ? null : text;
	}

	/** A plain-text excerpt of at most {@link NewArticle#MAX_EXCERPT} characters, ending in an ellipsis when cut. */
	String excerpt(String plainText) {
		if (plainText == null) {
			return "";
		}
		if (plainText.length() <= NewArticle.MAX_EXCERPT) {
			return plainText;
		}
		return cut(plainText, NewArticle.MAX_EXCERPT - ELLIPSIS.length()).stripTrailing() + ELLIPSIS;
	}

	/** Cuts to at most {@code max} characters without splitting a surrogate pair. */
	static String cut(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		int end = max;
		if (Character.isHighSurrogate(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end).stripTrailing();
	}

}
