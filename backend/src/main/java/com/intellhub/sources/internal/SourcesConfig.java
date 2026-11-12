package com.intellhub.sources.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SourcesProperties.class)
class SourcesConfig {

}
