package com.intellhub.ingestion.internal;

import com.intellhub.shared.PublicAddressPolicy;
import com.intellhub.shared.PublicUrlPolicy;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfig {

	/** The production fetcher: public URLs and public addresses only, no exceptions. */
	@Bean
	SafeHttpFetcher safeHttpFetcher(IngestionProperties properties) {
		FetchSettings settings = new FetchSettings(properties.connectTimeout(), properties.responseTimeout(),
				properties.totalTimeout(), properties.maxBodyBytes(), properties.maxRedirects(), "IntellHub/0.1");
		return new SafeHttpFetcher(settings, PublicUrlPolicy::problem, PublicAddressPolicy::isPublic,
				SystemDefaultDnsResolver.INSTANCE);
	}

}
