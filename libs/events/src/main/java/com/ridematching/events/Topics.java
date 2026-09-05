package com.ridematching.events;

/**
 * Topic names, declared once (ADR-0003).
 *
 * <p>The {@code .v1} suffix is part of the name on purpose: a breaking schema change becomes a
 * new topic with a dual-publish window, not a coordinated big-bang deploy where producers and
 * consumers must restart together.
 *
 * <p>A typo here is not a compile error at the broker — {@code auto.create.topics.enable} is
 * false precisely so a wrong name fails loudly instead of quietly creating a topic nobody
 * consumes. Constants make the typo impossible in the first place.
 */
public final class Topics {

    public static final String DRIVER_LOCATION = "driver.location.v1";
    public static final String RIDE_REQUESTED = "ride.requested.v1";
    public static final String RIDE_MATCHED = "ride.matched.v1";
    public static final String RIDE_UNMATCHED = "ride.unmatched.v1";
    public static final String RIDE_COMPLETED = "ride.completed.v1";
    public static final String RIDE_CANCELLED = "ride.cancelled.v1";

    /** Consumer groups. Separate groups are what make the fan-out independent. */
    public static final class Groups {
        public static final String MATCHING_ENGINE = "matching-engine";
        public static final String NOTIFICATION = "notification-service";
        public static final String BILLING = "billing-service";

        private Groups() {
        }
    }

    /** Dead-letter topic for {@code topic}, per the {@code *.dlt} convention. */
    public static String deadLetter(String topic) {
        return topic + ".dlt";
    }

    private Topics() {
    }
}
