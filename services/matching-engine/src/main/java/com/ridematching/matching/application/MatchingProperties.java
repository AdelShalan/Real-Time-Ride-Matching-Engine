package com.ridematching.matching.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Matching tuning. Every value is a trade-off between rider wait time and match quality, so
 * all of them are configuration the load tests can sweep rather than constants in code.
 */
@ConfigurationProperties(prefix = "ridematching.matching")
public class MatchingProperties {

    /**
     * Expanding search radii, in metres, tried in order.
     *
     * <p>Starting narrow keeps the common case cheap and the pickup close; widening only when
     * nothing is claimable avoids telling a rider there are no drivers when there is one a
     * little further out.
     */
    private List<Double> searchRadiiMeters = List.of(2_000.0, 3_200.0, 5_000.0);

    /** Candidates fetched per radius. Bounded because each one may cost a claim round trip. */
    private int candidateLimit = 30;

    /**
     * How long a claim is held before Redis expires it.
     *
     * <p>Longer than the offer TTL on purpose: the claim must outlive the offer so a driver
     * cannot be re-claimed in the gap between an offer expiring and the engine noticing.
     */
    private Duration claimTtl = Duration.ofSeconds(10);

    /** How long a driver has to accept before the offer lapses. */
    private Duration offerTtl = Duration.ofSeconds(8);

    /** Overall budget: after this, the request is reported UNMATCHED. */
    private Duration matchingSla = Duration.ofSeconds(30);

    public List<Double> getSearchRadiiMeters() {
        return searchRadiiMeters;
    }

    public void setSearchRadiiMeters(List<Double> searchRadiiMeters) {
        this.searchRadiiMeters = searchRadiiMeters;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public Duration getClaimTtl() {
        return claimTtl;
    }

    public void setClaimTtl(Duration claimTtl) {
        this.claimTtl = claimTtl;
    }

    public Duration getOfferTtl() {
        return offerTtl;
    }

    public void setOfferTtl(Duration offerTtl) {
        this.offerTtl = offerTtl;
    }

    public Duration getMatchingSla() {
        return matchingSla;
    }

    public void setMatchingSla(Duration matchingSla) {
        this.matchingSla = matchingSla;
    }
}
