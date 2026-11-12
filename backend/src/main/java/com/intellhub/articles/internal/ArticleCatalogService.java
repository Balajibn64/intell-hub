package com.intellhub.articles.internal;

import com.intellhub.articles.ArticleCatalog;
import com.intellhub.articles.ArticleFilter;
import com.intellhub.articles.ArticlePage;
import com.intellhub.articles.ArticleView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read queries over articles. The SQL text is assembled only from fixed fragments chosen by the code; every
 * value from a caller (search words, ids, category, paging) is a bound parameter, never part of the text.
 */
@Service
class ArticleCatalogService implements ArticleCatalog {

	private final JdbcClient jdbc;

	ArticleCatalogService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public ArticlePage search(ArticleFilter filter, int page, int size) {
		if (page < 0 || page > MAX_PAGE) {
			throw new IllegalArgumentException("page is out of range");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size is out of range");
		}
		ArticleFilter f = filter == null ? ArticleFilter.none() : filter;
		if (f.query() != null && f.query().length() > MAX_QUERY_LENGTH) {
			throw new IllegalArgumentException("query is too long");
		}
		String query = cleanQuery(f.query());
		if (f.category() != null && !CATEGORIES.contains(f.category())) {
			throw new IllegalArgumentException("unknown category");
		}
		if (f.onlyIds() != null && f.onlyIds().isEmpty()) {
			return new ArticlePage(List.of(), page, size, 0);
		}

		StringBuilder where = new StringBuilder(" where true");
		if (!query.isEmpty()) {
			where.append(" and ").append(ArticleSql.SEARCH_VECTOR).append(" @@ websearch_to_tsquery('english', :query)");
		}
		if (f.sourceId() != null) {
			where.append(" and source_id = :source");
		}
		if (f.category() != null) {
			where.append(" and category = :category");
		}
		if (f.onlyIds() != null) {
			where.append(" and id in (:ids)");
		}

		long total = bind(jdbc.sql("select count(*) from articles" + where), query, f).query(Long.class).single();
		List<ArticleView> items = bind(jdbc.sql("select " + ArticleSql.COLUMNS + " from articles" + where
				+ " order by published_at desc, id desc limit :limit offset :offset"), query, f)
				.param("limit", size).param("offset", (long) page * size)
				.query(LIST_ROWS).list();
		return new ArticlePage(items, page, size, total);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ArticleView> find(UUID id) {
		return jdbc.sql("select " + ArticleSql.COLUMNS + ", content_sanitized from articles where id = :id")
				.param("id", id).query(DETAIL_ROWS).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existsById(UUID id) {
		return jdbc.sql("select exists (select 1 from articles where id = :id)").param("id", id)
				.query(Boolean.class).single();
	}

	/**
	 * PostgreSQL refuses NUL bytes and lone surrogates in text, which would surface as a server error for
	 * whoever typed them. Control characters become spaces and lone surrogates are dropped.
	 */
	static String cleanQuery(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder cleaned = new StringBuilder(raw.length());
		raw.codePoints().forEach(codePoint -> {
			if (Character.isISOControl(codePoint)) {
				cleaned.append(' ');
			}
			else if (Character.getType(codePoint) != Character.SURROGATE) {
				cleaned.appendCodePoint(codePoint);
			}
		});
		return cleaned.toString().strip();
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, String query, ArticleFilter f) {
		if (!query.isEmpty()) {
			spec = spec.param("query", query);
		}
		if (f.sourceId() != null) {
			spec = spec.param("source", f.sourceId());
		}
		if (f.category() != null) {
			spec = spec.param("category", f.category());
		}
		if (f.onlyIds() != null) {
			spec = spec.param("ids", f.onlyIds());
		}
		return spec;
	}

	private static final RowMapper<ArticleView> LIST_ROWS = (rs, rowNum) -> map(rs, null);

	private static final RowMapper<ArticleView> DETAIL_ROWS = (rs, rowNum) -> map(rs, rs.getString("content_sanitized"));

	private static ArticleView map(ResultSet rs, String content) throws SQLException {
		return new ArticleView(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
				rs.getString("canonical_url"), rs.getString("title"), rs.getString("author"),
				rs.getObject("published_at", OffsetDateTime.class).toInstant(),
				rs.getObject("ingested_at", OffsetDateTime.class).toInstant(), rs.getString("excerpt"),
				rs.getString("summary"), rs.getString("category"), rs.getString("status"), content);
	}

}
