package com.intellhub.articles;

import java.util.List;

/** One page of search results. Pages start at 0. */
public record ArticlePage(List<ArticleView> items, int page, int size, long totalElements) {

	public int totalPages() {
		return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
	}

}
