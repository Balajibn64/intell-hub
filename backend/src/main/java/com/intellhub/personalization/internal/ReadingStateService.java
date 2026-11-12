package com.intellhub.personalization.internal;

import com.intellhub.articles.ArticleCatalog;
import com.intellhub.personalization.ReadingState;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain SQL on purpose: these are small set operations (insert if absent, delete, count what changed),
 * and "insert only if absent" is a single atomic statement, so concurrent saves cannot double up.
 */
@Service
class ReadingStateService implements ReadingState {

	private static final String CLICK = "CLICK";

	private static final String SAVE = "SAVE";

	private static final String UNSAVE = "UNSAVE";

	private final JdbcClient jdbc;

	private final Clock clock;

	private final ArticleCatalog articles;

	ReadingStateService(JdbcClient jdbc, Clock clock, ArticleCatalog articles) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.articles = articles;
	}

	@Override
	@Transactional
	public boolean save(UUID userId, UUID articleId) {
		int inserted = jdbc.sql("""
				insert into saved_articles (user_id, article_id, saved_at) values (:user, :article, :now)
				on conflict (user_id, article_id) do nothing""")
				.param("user", userId).param("article", articleId).param("now", now()).update();
		if (inserted == 0) {
			return false;
		}
		recordEvent(userId, articleId, SAVE);
		return true;
	}

	@Override
	@Transactional
	public boolean unsave(UUID userId, UUID articleId) {
		int deleted = jdbc.sql("delete from saved_articles where user_id = :user and article_id = :article")
				.param("user", userId).param("article", articleId).update();
		if (deleted == 0) {
			return false;
		}
		recordEvent(userId, articleId, UNSAVE);
		return true;
	}

	@Override
	@Transactional
	public void recordClick(UUID userId, UUID articleId) {
		// Two clicks arriving together would both see "no recent click" under READ COMMITTED. A transaction-level
		// advisory lock on this user and article makes the second wait, and then see the first click.
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", userId + ":" + articleId)
				.query().singleRow();
		boolean recentClick = jdbc.sql("""
				select exists (select 1 from article_events where user_id = :user and article_id = :article
				and type = 'CLICK' and created_at > :since)""")
				.param("user", userId).param("article", articleId)
				.param("since", now().minusSeconds(CLICK_DEDUPE_SECONDS)).query(Boolean.class).single();
		if (!recentClick) {
			recordEvent(userId, articleId, CLICK);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> savedArticleIds(UUID userId) {
		return jdbc.sql("""
				select article_id from saved_articles where user_id = :user
				order by saved_at desc, article_id limit :limit""")
				.param("user", userId).param("limit", MAX_SAVED_LISTED).query(UUID.class).list();
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> savedAmong(UUID userId, Collection<UUID> articleIds) {
		if (articleIds == null || articleIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("select article_id from saved_articles where user_id = :user and article_id in (:ids)")
				.param("user", userId).param("ids", articleIds).query(UUID.class).list());
	}

	private void recordEvent(UUID userId, UUID articleId, String type) {
		jdbc.sql("""
				insert into article_events (id, user_id, article_id, type, created_at)
				values (:id, :user, :article, :type, :now)""")
				.param("id", UUID.randomUUID()).param("user", userId).param("article", articleId)
				.param("type", type).param("now", now()).update();
		learn(userId, articleId, switch (type) {
			case CLICK -> Weights.CLICK_STEP;
			case SAVE -> Weights.SAVE_STEP;
			default -> Weights.UNSAVE_STEP;
		});
	}

	/**
	 * Nudges the user's learned weight for every tag of the article, in the same transaction as the event, so a
	 * recorded event and its effect never come apart. Each tag is one atomic statement: the stored weight is
	 * faded to now, the step is added, and the result is kept between 0 and 1. Tags are handled in id order so
	 * two requests of the same user never wait on each other in opposite orders.
	 */
	private void learn(UUID userId, UUID articleId, double step) {
		List<UUID> tagIds = articles.tagIdsOf(articleId).stream().sorted().toList();
		for (UUID tagId : tagIds) {
			if (step > 0) {
				jdbc.sql("""
						insert into user_tag_weights (user_id, tag_id, explicit_weight, learned_weight, updated_at)
						values (:user, :tag, 0, least(1, :step), :now)
						on conflict (user_id, tag_id) do update
						set learned_weight = greatest(0, least(1, %s + :step)), updated_at = :now"""
						.formatted(Weights.fadedLearned("user_tag_weights")))
						.param("user", userId).param("tag", tagId).param("step", step).param("now", now()).update();
			}
			else {
				jdbc.sql("""
						update user_tag_weights set learned_weight = greatest(0, least(1, %s + :step)), updated_at = :now
						where user_id = :user and tag_id = :tag""".formatted(Weights.FADED_LEARNED))
						.param("user", userId).param("tag", tagId).param("step", step).param("now", now()).update();
			}
		}
		if (step < 0 && !tagIds.isEmpty()) {
			jdbc.sql("""
					delete from user_tag_weights
					where user_id = :user and tag_id in (:tags) and explicit_weight = 0 and learned_weight = 0""")
					.param("user", userId).param("tags", tagIds).update();
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
