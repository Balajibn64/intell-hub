package com.intellhub.personalization;

import java.util.List;

/** Some of the tags asked for do not exist. */
public class UnknownTagException extends RuntimeException {

	private final List<String> tags;

	public UnknownTagException(List<String> tags) {
		super("Unknown tags", null, false, false);
		this.tags = List.copyOf(tags);
	}

	public List<String> tags() {
		return tags;
	}

}
