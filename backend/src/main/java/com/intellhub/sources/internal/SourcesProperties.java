package com.intellhub.sources.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param seedDefaults add the starter feeds from default-sources.json on startup, but only while there are no sources
 */
@ConfigurationProperties("intellhub.sources")
record SourcesProperties(@DefaultValue("true") boolean seedDefaults) {
}
