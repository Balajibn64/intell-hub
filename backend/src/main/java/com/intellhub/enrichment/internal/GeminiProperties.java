package com.intellhub.enrichment.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Gemini settings. The model name goes into the request address, so it is limited to safe characters. The base
 * address can only be changed to another https address or the local machine (tests); it is not an environment
 * variable.
 *
 * @param apiKey from {@code GEMINI_API_KEY}; never printed
 */
@ConfigurationProperties("intellhub.enrichment.gemini")
@Validated
record GeminiProperties(
		@DefaultValue("") String apiKey,
		@DefaultValue("gemini-3.5-flash-lite") @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String model,
		@DefaultValue("https://generativelanguage.googleapis.com") @NotBlank String baseUrl) {

	@Override
	public String toString() {
		return "GeminiProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "set") + ", model=" + model + "]";
	}

}
