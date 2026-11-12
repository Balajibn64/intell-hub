package com.intellhub.enrichment.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Groq settings. The base address can only be changed to another https address or the local machine (tests); it
 * is not an environment variable.
 *
 * @param apiKey from {@code GROQ_API_KEY}; never printed
 */
@ConfigurationProperties("intellhub.enrichment.groq")
@Validated
record GroqProperties(
		@DefaultValue("") String apiKey,
		@DefaultValue("openai/gpt-oss-20b") @Pattern(regexp = "[A-Za-z0-9._/-]{1,80}") String model,
		@DefaultValue("https://api.groq.com") @NotBlank String baseUrl) {

	@Override
	public String toString() {
		return "GroqProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "set") + ", model=" + model + "]";
	}

}
