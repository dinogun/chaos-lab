package ai.causa.libertyperf.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents an airline booking / reservation.
 */
public class Booking {

    public enum BookingStatus { CONFIRMED, PENDING, CANCELLED, WAITLISTED }

    private String        bookingRef;
    private String        passengerId;
    private String        passengerName;
    private String        origin;
    private String        destination;
    private String        flightNumber;
    private Instant       departureTime;
    private Instant       arrivalTime;
    private String        seatClass;     // ECONOMY, BUSINESS, FIRST
    private String        seatNumber;
    private BigDecimal    fare;
    private String        currency;
    private BookingStatus status;
    private Instant       bookedAt;
    private String        correlationId;

    public Booking() {}

    // ---- getters / setters ----

    public String getBookingRef()                  { return bookingRef; }
    public void setBookingRef(String v)            { this.bookingRef = v; }

    public String getPassengerId()                 { return passengerId; }
    public void setPassengerId(String v)           { this.passengerId = v; }

    public String getPassengerName()               { return passengerName; }
    public void setPassengerName(String v)         { this.passengerName = v; }

    public String getOrigin()                      { return origin; }
    public void setOrigin(String v)                { this.origin = v; }

    public String getDestination()                 { return destination; }
    public void setDestination(String v)           { this.destination = v; }

    public String getFlightNumber()                { return flightNumber; }
    public void setFlightNumber(String v)          { this.flightNumber = v; }

    public Instant getDepartureTime()              { return departureTime; }
    public void setDepartureTime(Instant v)        { this.departureTime = v; }

    public Instant getArrivalTime()                { return arrivalTime; }
    public void setArrivalTime(Instant v)          { this.arrivalTime = v; }

    public String getSeatClass()                   { return seatClass; }
    public void setSeatClass(String v)             { this.seatClass = v; }

    public String getSeatNumber()                  { return seatNumber; }
    public void setSeatNumber(String v)            { this.seatNumber = v; }

    public BigDecimal getFare()                    { return fare; }
    public void setFare(BigDecimal v)              { this.fare = v; }

    public String getCurrency()                    { return currency; }
    public void setCurrency(String v)              { this.currency = v; }

    public BookingStatus getStatus()               { return status; }
    public void setStatus(BookingStatus v)         { this.status = v; }

    public Instant getBookedAt()                   { return bookedAt; }
    public void setBookedAt(Instant v)             { this.bookedAt = v; }

    public String getCorrelationId()               { return correlationId; }
    public void setCorrelationId(String v)         { this.correlationId = v; }
}
