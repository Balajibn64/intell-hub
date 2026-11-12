package com.intellhub.sources;

/**
 * The outcome of one fetch attempt, reported back to the sources module for health tracking.
 *
 * @param success whether the fetch worked
 * @param error short reason for a failure; never put response bodies or secrets here
 */
public record FetchResult(boolean success, String error) {

	public static FetchResult ok() {
		return new FetchResult(true, null);
	}

	public static FetchResult failed(String error) {
		return new FetchResult(false, error);
	}

}
