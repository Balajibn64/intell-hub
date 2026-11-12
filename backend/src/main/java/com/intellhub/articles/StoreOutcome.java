package com.intellhub.articles;

/** What happened when an article was offered to the store. */
public enum StoreOutcome {

	/** It was new and is now stored. */
	CREATED,

	/** An article with the same canonical URL already exists. */
	DUPLICATE_URL,

	/** The same content already exists under another URL. */
	DUPLICATE_CONTENT

}
