package com.intellhub.articles;

import java.util.UUID;

/** An article waiting for the cheap pre-filter. Text fields are plain text. */
public record TriageCandidate(UUID id, String title, String excerpt) {
}
