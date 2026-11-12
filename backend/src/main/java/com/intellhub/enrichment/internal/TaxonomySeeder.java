package com.intellhub.enrichment.internal;

import com.intellhub.articles.ArticleEnrichment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the curated tags at startup. Safe to repeat: existing tags are left alone, and a free tag that
 * the model happened to create first is promoted to a curated one. The list lives in taxonomy.json so it can
 * be reviewed and edited without a migration.
 */
@Component
class TaxonomySeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TaxonomySeeder.class);

	private final Taxonomy taxonomy;

	private final ArticleEnrichment articles;

	private final EnrichmentProperties properties;

	TaxonomySeeder(Taxonomy taxonomy, ArticleEnrichment articles, EnrichmentProperties properties) {
		this.taxonomy = taxonomy;
		this.articles = articles;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.seedTaxonomy()) {
			return;
		}
		articles.upsertTaxonomyTags(taxonomy.names());
		log.info("Taxonomy ready: {} curated tags", taxonomy.names().size());
	}

}
