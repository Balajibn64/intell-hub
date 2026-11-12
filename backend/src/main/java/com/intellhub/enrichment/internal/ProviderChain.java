package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tries the configured providers in order and falls through to the next when one fails. The worker only ever
 * talks to the chain. It is empty (and enrichment is off) when no provider is configured.
 */
@Component
class ProviderChain {

	/** The results together with the name of the provider that produced them. */
	record Answer(String provider, List<SummaryResult> results) {
	}

	private final List<SummarizerProvider> providers;

	ProviderChain(List<SummarizerProvider> providers) {
		this.providers = List.copyOf(providers);
	}

	boolean hasProviders() {
		return !providers.isEmpty();
	}

	/** @throws ProviderException the last provider's error if every provider failed, or UNAVAILABLE if there are none */
	Answer summarize(List<SummaryItem> batch) throws ProviderException {
		ProviderException last = new ProviderException(ProviderException.Kind.UNAVAILABLE, "No provider is configured");
		for (SummarizerProvider provider : providers) {
			try {
				return new Answer(provider.name(), provider.summarize(batch));
			}
			catch (ProviderException e) {
				last = e;
			}
		}
		throw last;
	}

}
