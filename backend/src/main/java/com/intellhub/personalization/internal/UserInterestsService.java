package com.intellhub.personalization.internal;

import com.intellhub.articles.ArticleCatalog;
import com.intellhub.personalization.Interest;
import com.intellhub.personalization.InterestView;
import com.intellhub.personalization.UnknownTagException;
import com.intellhub.personalization.UserInterests;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plain SQL; every value is a bound parameter and the only SQL text pieces are fixed constants. */
@Service
class UserInterestsService implements UserInterests {

	private record Row(UUID tagId, double explicit, double learned) {
	}

	private final JdbcClient jdbc;

	private final ArticleCatalog articles;

	private final Clock clock;

	UserInterestsService(JdbcClient jdbc, ArticleCatalog articles, Clock clock) {
		this.jdbc = jdbc;
		this.articles = articles;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void replaceInterests(UUID userId, List<Interest> interests) {
		if (interests == null || interests.size() > MAX_INTERESTS) {
			throw new IllegalArgumentException("too many interests");
		}
		Map<String, Double> chosen = new LinkedHashMap<>();
		for (Interest interest : interests) {
			double weight = interest.weight();
			if (interest.tag() == null || !(weight >= MIN_WEIGHT && weight <= MAX_WEIGHT)) { // also rejects NaN
				throw new IllegalArgumentException("invalid interest");
			}
			chosen.merge(interest.tag(), weight, Math::max);
		}
		Map<String, UUID> ids = articles.tagIds(chosen.keySet());
		List<String> unknown = chosen.keySet().stream().filter(name -> !ids.containsKey(name)).toList();
		if (!unknown.isEmpty()) {
			throw new UnknownTagException(unknown);
		}

		// One replacement at a time per user, so two requests cannot interleave their delete and insert steps.
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "interests:" + userId)
				.query().singleRow();
		if (ids.isEmpty()) {
			jdbc.sql("update user_tag_weights set explicit_weight = 0 where user_id = :user and explicit_weight > 0")
					.param("user", userId).update();
		}
		else {
			jdbc.sql("""
					update user_tag_weights set explicit_weight = 0
					where user_id = :user and explicit_weight > 0 and tag_id not in (:ids)""")
					.param("user", userId).param("ids", ids.values()).update();
		}
		// updated_at is when the learned part last changed, so choosing a tag must not restart its fading.
		for (Map.Entry<String, Double> entry : chosen.entrySet()) {
			jdbc.sql("""
					insert into user_tag_weights (user_id, tag_id, explicit_weight, learned_weight, updated_at)
					values (:user, :tag, :weight, 0, :now)
					on conflict (user_id, tag_id) do update set explicit_weight = :weight""")
					.param("user", userId).param("tag", ids.get(entry.getKey())).param("weight", entry.getValue())
					.param("now", now()).update();
		}
		jdbc.sql("delete from user_tag_weights where user_id = :user and explicit_weight = 0 and learned_weight = 0")
				.param("user", userId).update();
	}

	@Override
	@Transactional(readOnly = true)
	public List<InterestView> interests(UUID userId) {
		List<Row> rows = rows(userId).stream()
				.filter(r -> r.explicit() > 0 || r.learned() >= Weights.LISTING_THRESHOLD)
				.sorted(Comparator.comparingDouble((Row r) -> r.explicit() + r.learned()).reversed()).limit(MAX_LISTED).toList();
		Map<UUID, String> names = articles.tagNames(rows.stream().map(Row::tagId).toList());
		List<InterestView> views = new ArrayList<>();
		for (Row row : rows) {
			String name = names.get(row.tagId());
			if (name != null) {
				views.add(new InterestView(name, row.explicit(), row.learned()));
			}
		}
		return views;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Double> rankingWeights(UUID userId) {
		List<Row> rows = rows(userId).stream().filter(r -> r.explicit() + r.learned() > 0.001)
				.sorted(Comparator.comparingDouble((Row r) -> r.explicit() + r.learned()).reversed()).limit(MAX_LISTED).toList();
		Map<UUID, String> names = articles.tagNames(rows.stream().map(Row::tagId).toList());
		Map<String, Double> weights = new LinkedHashMap<>();
		for (Row row : rows) {
			String name = names.get(row.tagId());
			if (name != null) {
				weights.put(name, row.explicit() + row.learned());
			}
		}
		return weights;
	}

	private List<Row> rows(UUID userId) {
		return jdbc.sql("select tag_id, explicit_weight, " + Weights.FADED_LEARNED + " as learned from user_tag_weights "
				+ "where user_id = :user").param("user", userId).param("now", now())
				.query((rs, n) -> new Row(rs.getObject("tag_id", UUID.class), rs.getDouble("explicit_weight"),
						rs.getDouble("learned")))
				.list();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
