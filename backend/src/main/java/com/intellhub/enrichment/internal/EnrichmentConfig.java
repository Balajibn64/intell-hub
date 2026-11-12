package com.intellhub.enrichment.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
class EnrichmentConfig {

}
