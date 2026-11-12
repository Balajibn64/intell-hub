package com.intellhub.ingestion.internal;

import com.intellhub.sources.SourceType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Which parser reads which source type. Two parsers claiming one type is a mistake that stops startup. */
@Component
class ParserRegistry {

	private final Map<SourceType, SourceParser> byType = new EnumMap<>(SourceType.class);

	ParserRegistry(List<SourceParser> parsers) {
		for (SourceParser parser : parsers) {
			for (SourceType type : parser.types()) {
				if (byType.put(type, parser) != null) {
					throw new IllegalStateException("More than one parser is registered for source type " + type);
				}
			}
		}
	}

	/** The parser for the type, or null if none exists (the source is then skipped as unsupported). */
	SourceParser forType(SourceType type) {
		return byType.get(type);
	}

}
