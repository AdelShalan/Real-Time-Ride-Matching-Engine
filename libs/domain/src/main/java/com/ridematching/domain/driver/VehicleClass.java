package com.ridematching.domain.driver;

/**
 * Vehicle tier a rider can request. Used as a hard filter during candidate selection:
 * a rider requesting {@link #XL} is never offered a {@link #STANDARD} vehicle.
 */
public enum VehicleClass {

    /** Four seats. The default tier. */
    STANDARD(4),

    /** Six or more seats. */
    XL(6),

    /** Four seats, premium vehicle. */
    PREMIUM(4);

    private final int seats;

    VehicleClass(int seats) {
        this.seats = seats;
    }

    public int seats() {
        return seats;
    }

    /** Whether a vehicle of this class can serve a request for {@code requested}. */
    public boolean canServe(VehicleClass requested) {
        return this == requested;
    }
}
