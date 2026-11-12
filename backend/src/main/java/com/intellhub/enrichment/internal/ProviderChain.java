package com.intellhub.enrichment.internal;

import com.intellhub.enrichment.ProviderException;
import com.intellhub.enrichment.SummarizerProvider;
import com.intellhub.enrichment.SummaryItem;
import com.intellhub.enrichment.SummaryResult;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tries the configured providers in order and falls through to the next when one fails. The worker only ever
 * talks to the chain. It is empty (and enrichment is off) when no provider is configured.
 *
 * <p>Every attempt spends one call from the daily budget, because a real provider call counts against its
 * free-tier limit whether or not it succeeds.
 */
@Component
class ProviderChain {

	/** The results together with the name of the provider that produced them. */
	record Answer(String provider, String tagOrigin, List<SummaryResult> results) {
	}

	private final List<SummarizerProvider> providers;

	private final CallBudget budget;

	/** The providers in the order they are configured: the first is tried first, the rest are fallbacks. */
	@Autowired
	ProviderChain(List<SummarizerProvider> providers, CallBudget budget, EnrichmentProperties properties) {
		this(inConfiguredOrder(providers, properties.providers()), budget);
	}

	ProviderChain(List<SummarizerProvider> providers, CallBudget budget) {
		this.providers = List.copyOf(providers);
		this.budget = budget;
	}

	private static List<SummarizerProvider> inConfiguredOrder(List<SummarizerProvider> providers, List<String> order) {
		return providers.stream().sorted(Comparator.comparingInt(p -> {
			int position = order.indexOf(p.name());
			return position < 0 ? Integer.MAX_VALUE : position;
		})).toList();
	}

	List<String> providerNames() {
		return providers.stream().map(SummarizerProvider::name).toList();
	}

	boolean hasProviders() {
		return !providers.isEmpty();
	}

	/**
	 * @throws BudgetExhaustedException if today's budget is spent before any provider answered
	 * @throws ProviderException the last provider's error if every provider failed, or UNAVAILABLE if there are none
	 */
	Answer summarize(List<SummaryItem> batch, BudgetPriority priority) throws ProviderException, BudgetExhaustedException {
		ProviderException last = new ProviderException(ProviderException.Kind.UNAVAILABLE, "No provider is configured");
		for (SummarizerProvider provider : providers) {
			if (!budget.tryReserve(priority, provider.name())) {
				throw new BudgetExhaustedException();
			}
			try {
				return new Answer(provider.name(), provider.tagOrigin(), provider.summarize(batch));
			}
			catch (ProviderException e) {
				budget.recordFailure(provider.name());
				last = e;
			}
			catch (RuntimeException e) {
				// A provider bug must not take the worker down, and its message could contain article text.
				budget.recordFailure(provider.name());
				last = new ProviderException(ProviderException.Kind.UNAVAILABLE,
						"Provider failed unexpectedly: " + e.getClass().getSimpleName());
			}
		}
		throw last;
	}

}
