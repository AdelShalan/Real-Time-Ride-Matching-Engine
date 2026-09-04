package com.ridematching.domain.trip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ridematching.domain.trip.TripStatus.ACCEPTED;
import static com.ridematching.domain.trip.TripStatus.CANCELLED;
import static com.ridematching.domain.trip.TripStatus.COMPLETED;
import static com.ridematching.domain.trip.TripStatus.IN_PROGRESS;
import static com.ridematching.domain.trip.TripStatus.MATCHING;
import static com.ridematching.domain.trip.TripStatus.OFFERED;
import static com.ridematching.domain.trip.TripStatus.REQUESTED;
import static com.ridematching.domain.trip.TripStatus.UNMATCHED;
import static org.assertj.core.api.Assertions.assertThat;

class TripStatusTest {

    /**
     * The complete set of legal transitions, written out independently of the production
     * table. The exhaustive test below compares every one of the 8x8 pairs against this,
     * so a transition silently added to {@link TripStatus} fails the build rather than
     * quietly widening what the system permits.
     */
    private static final Set<List<TripStatus>> LEGAL = Set.of(
            List.of(REQUESTED, MATCHING),
            List.of(REQUESTED, CANCELLED),
            List.of(MATCHING, OFFERED),
            List.of(MATCHING, UNMATCHED),
            List.of(MATCHING, CANCELLED),
            List.of(OFFERED, ACCEPTED),
            List.of(OFFERED, MATCHING),
            List.of(OFFERED, CANCELLED),
            List.of(ACCEPTED, IN_PROGRESS),
            List.of(ACCEPTED, CANCELLED),
            List.of(IN_PROGRESS, COMPLETED));

    @Test
    @DisplayName("every one of the 64 state pairs matches the specification exactly")
    void transitionTableIsExactlyAsSpecified() {
        List<String> mismatches = new ArrayList<>();

        for (TripStatus from : TripStatus.values()) {
            for (TripStatus to : TripStatus.values()) {
                boolean expected = LEGAL.contains(List.of(from, to));
                boolean actual = from.canTransitionTo(to);
                if (expected != actual) {
                    mismatches.add("%s -> %s: expected %s but was %s"
                            .formatted(from, to, expected ? "legal" : "illegal",
                                    actual ? "legal" : "illegal"));
                }
            }
        }

        assertThat(mismatches)
                .as("transition table drifted from the specification")
                .isEmpty();
    }

    @Test
    @DisplayName("a trip can never return to REQUESTED")
    void nothingReturnsToRequested() {
        for (TripStatus from : TripStatus.values()) {
            assertThat(from.canTransitionTo(REQUESTED))
                    .as("%s -> REQUESTED must be illegal", from)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("terminal states are exactly COMPLETED, CANCELLED and UNMATCHED")
    void terminalStates() {
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(UNMATCHED.isTerminal()).isTrue();

        assertThat(REQUESTED.isTerminal()).isFalse();
        assertThat(MATCHING.isTerminal()).isFalse();
        assertThat(OFFERED.isTerminal()).isFalse();
        assertThat(ACCEPTED.isTerminal()).isFalse();
        assertThat(IN_PROGRESS.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TripStatus.class, names = {"COMPLETED", "CANCELLED", "UNMATCHED"})
    @DisplayName("terminal states have no way out")
    void terminalStatesHaveNoOutgoingTransitions(TripStatus terminal) {
        assertThat(terminal.allowedTargets()).isEmpty();

        for (TripStatus target : TripStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("IN_PROGRESS cannot be cancelled, only completed")
    void inProgressCannotBeCancelled() {
        assertThat(IN_PROGRESS.canTransitionTo(CANCELLED)).isFalse();
        assertThat(IN_PROGRESS.allowedTargets()).containsExactly(COMPLETED);
    }

    @Test
    @DisplayName("driver-holding states match the unique index predicate from ADR-0004")
    void driverHoldingStatesMatchTheDatabaseConstraint() {
        // If this ever disagrees with uniq_driver_active_trip, the concurrency guarantee
        // has a hole: a state the domain treats as holding a driver but the index ignores.
        assertThat(OFFERED.holdsDriver()).isTrue();
        assertThat(ACCEPTED.holdsDriver()).isTrue();
        assertThat(IN_PROGRESS.holdsDriver()).isTrue();

        assertThat(REQUESTED.holdsDriver()).isFalse();
        assertThat(MATCHING.holdsDriver()).isFalse();
        assertThat(COMPLETED.holdsDriver()).isFalse();
        assertThat(CANCELLED.holdsDriver()).isFalse();
        assertThat(UNMATCHED.holdsDriver()).isFalse();
    }

    @Test
    @DisplayName("null target is rejected rather than throwing")
    void nullTargetIsIllegalNotFatal() {
        assertThat(REQUESTED.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("allowedTargets is unmodifiable")
    void allowedTargetsCannotBeMutated() {
        Set<TripStatus> targets = REQUESTED.allowedTargets();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> targets.add(COMPLETED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("every non-terminal state can reach a terminal state")
    void noDeadEnds() {
        // Guards against a state that is neither terminal nor able to finish — a trip
        // that could get stuck forever.
        for (TripStatus from : TripStatus.values()) {
            if (from.isTerminal()) {
                continue;
            }
            assertThat(reachesTerminal(from, new ArrayList<>()))
                    .as("%s must be able to reach a terminal state", from)
                    .isTrue();
        }
    }

    private boolean reachesTerminal(TripStatus from, List<TripStatus> visited) {
        if (from.isTerminal()) {
            return true;
        }
        if (visited.contains(from)) {
            return false;
        }
        visited.add(from);
        return from.allowedTargets().stream().anyMatch(next -> reachesTerminal(next, visited));
    }
}
