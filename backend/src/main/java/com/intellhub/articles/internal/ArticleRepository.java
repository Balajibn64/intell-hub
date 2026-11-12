package com.intellhub.articles.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleRepository extends JpaRepository<Article, UUID> {

	boolean existsByCanonicalUrl(String canonicalUrl);

	boolean existsByContentHash(String contentHash);

}
