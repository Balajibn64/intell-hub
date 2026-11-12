package com.intellhub.sources.internal;

import com.intellhub.sources.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SourceRepository extends JpaRepository<Source, UUID> {

	List<Source> findAllByOrderByCreatedAtAsc();

	List<Source> findByEnabledTrueOrderByCreatedAtAsc();

	boolean existsByTypeAndUrl(SourceType type, String url);

	/** Single atomic UPDATEs, so concurrent reports never lose a failure count. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Source s set s.lastFetchedAt = :at, s.lastStatus = :status, s.lastError = null,
			s.consecutiveFailures = 0 where s.id = :id""")
	int markSuccess(@Param("id") UUID id, @Param("at") Instant at, @Param("status") FetchStatus status);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Source s set s.lastFetchedAt = :at, s.lastStatus = :status, s.lastError = :error,
			s.consecutiveFailures = case when s.consecutiveFailures < 1000000 then s.consecutiveFailures + 1
			else s.consecutiveFailures end where s.id = :id""")
	int markFailure(@Param("id") UUID id, @Param("at") Instant at, @Param("status") FetchStatus status,
			@Param("error") String error);

}
