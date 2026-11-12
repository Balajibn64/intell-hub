package com.intellhub.retention;

import java.time.Instant;

/**
 * What one purge did.
 *
 * @param dryRun nothing was deleted; the other numbers say what a real run would do (except tokens, which a dry run skips)
 * @param articlesExpired articles older than the retention window that were looked at
 * @param articlesKeptBecauseSaved of those, how many were kept because a user has them saved
 * @param articlesDeleted articles actually deleted (0 in a dry run)
 * @param tokensDeleted expired refresh tokens deleted (0 in a dry run)
 * @param durationMillis how long the run took
 * @param finishedAt when it finished
 */
public record RetentionResult(boolean dryRun, long articlesExpired, long articlesKeptBecauseSaved, long articlesDeleted,
		long tokensDeleted, long durationMillis, Instant finishedAt) {
}
