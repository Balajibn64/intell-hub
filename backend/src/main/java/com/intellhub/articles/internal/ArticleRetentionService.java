package com.intellhub.articles.internal;

import com.intellhub.articles.ArticleRetention;
import com.intellhub.articles.ArticleStatistics;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL; every value is a bound parameter. */
@Service
class ArticleRetentionService implements ArticleRetention, ArticleStatistics {

	static final int MAX_BATCH = 5000;

	private static final List<String> STATUSES = List.of("EXCERPT_ONLY", "PENDING", "ENRICHING", "ENRICHED", "FAILED");

	private final JdbcClient jdbc;

	ArticleRetentionService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public List<UUID> lockExpired(Instant cutoff, UUID after, int limit) {
		return select(cutoff, after, limit, true);
	}

	/** Both variants are fixed text; only the flag chooses whether the rows are locked. */
	private List<UUID> select(Instant cutoff, UUID after, int limit, boolean lock) {
		if (cutoff == null || limit < 1 || limit > MAX_BATCH) {
			throw new IllegalArgumentException("invalid purge batch");
		}
		OffsetDateTime before = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
		String suffix = lock ? " for update" : "";
		if (after == null) {
			return jdbc.sql("select id from articles where published_at < :cutoff order by id limit :limit" + suffix)
					.param("cutoff", before).param("limit", limit).query(UUID.class).list();
		}
		return jdbc.sql("select id from articles where published_at < :cutoff and id > :after order by id limit :limit" + suffix)
				.param("cutoff", before).param("after", after).param("limit", limit).query(UUID.class).list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> listExpired(Instant cutoff, UUID after, int limit) {
		return select(cutoff, after, limit, false);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int delete(Collection<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return 0;
		}
		return jdbc.sql("delete from articles where id in (:ids)").param("ids", ids).update();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Long> countsByStatus() {
		Map<String, Long> counts = new LinkedHashMap<>();
		STATUSES.forEach(status -> counts.put(status, 0L));
		jdbc.sql("select status, count(*) as n from articles group by status")
				.query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n"))).list()
				.forEach(e -> counts.put(e.getKey(), e.getValue()));
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public long total() {
		return jdbc.sql("select count(*) from articles").query(Long.class).single();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> oldestPublishedAt() {
		List<OffsetDateTime> oldest = jdbc.sql("select min(published_at) from articles")
				.query((rs, i) -> rs.getObject(1, OffsetDateTime.class)).list();
		return Optional.ofNullable(oldest.isEmpty() ? null : oldest.get(0)).map(OffsetDateTime::toInstant);
	}

}
