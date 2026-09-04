package com.ridematching.location.adapters.in.ws;

/**
 * Wire format for one GPS report. Short field names on purpose: at 10,000 frames per second
 * the JSON keys are a measurable share of the ~1.2 MB/s ingest bandwidth.
 *
 * <p>Note there is no driver id — it comes from the authenticated session, never the payload.
 *
 * <p>Deliberately a separate type from {@code DriverLocation}: the domain record must not
 * carry Jackson annotations or be shaped by wire concerns (ADR-0001). Protobuf is the
 * documented upgrade path if JSON parsing shows up in the profile.
 *
 * @param lat      latitude, degrees
 * @param lng      longitude, degrees
 * @param heading  compass bearing 0..360
 * @param speedKph ground speed
 * @param ts       device timestamp, epoch millis
 */
public record LocationFrame(double lat, double lng, double heading, double speedKph, long ts) {
}
