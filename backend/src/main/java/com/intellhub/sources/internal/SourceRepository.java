package com.intellhub.sources.internal;

import com.intellhub.sources.SourceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceRepository extends JpaRepository<Source, UUID> {

	List<Source> findAllByOrderByCreatedAtAsc();

	List<Source> findByEnabledTrueOrderByCreatedAtAsc();

	boolean existsByTypeAndUrl(SourceType type, String url);

}
