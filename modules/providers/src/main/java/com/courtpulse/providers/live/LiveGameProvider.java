package com.courtpulse.providers.live;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Live provider boundary. Implementations own HTTP, quotas, and provider schemas; everything
 * returned is already validated and mapped to provider-neutral records.
 */
public interface LiveGameProvider {
    String source();

    /** Games scheduled on the provider's league dates, with current provider status. */
    List<ProviderGame> discover(List<LocalDate> leagueDates);

    Optional<ProviderGame> game(String providerGameId);

    /** Every play the provider currently reports for one game, ordered by sequence. */
    List<ProviderPlay> plays(ProviderGame game);

    Optional<ProviderPlayer> player(String providerPlayerId);

    /** The provider's ID for a canonical player ID this provider issued, if it did. */
    Optional<String> providerPlayerId(String playerId);

    /** Request, throttling, and failure counts since the previous call. */
    ProviderClientStats drainStats();
}
