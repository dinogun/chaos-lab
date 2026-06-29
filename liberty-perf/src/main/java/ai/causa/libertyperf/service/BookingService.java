package ai.causa.libertyperf.service;

import ai.causa.libertyperf.model.Booking;
import ai.causa.libertyperf.repository.BookingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

/**
 * Business logic for airline booking operations.
 */
@ApplicationScoped
public class BookingService {

    private static final Logger LOG = Logger.getLogger(BookingService.class.getName());

    private static final String[] AIRPORTS   = {"JFK","LAX","ORD","ATL","DFW","DEN","SFO","SEA","LAS","MCO"};
    private static final String[] SEAT_CLASS = {"ECONOMY","BUSINESS","FIRST"};

    @Inject
    BookingRepository bookingRepository;

    // -------------------------------------------------------------------------
    // Booking operations
    // -------------------------------------------------------------------------

    @Counted(name = "bookings.created", description = "Total flight bookings created")
    @Timed(name = "booking.create.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to create a flight booking")
    public Booking createBooking(String passengerId, String passengerName,
                                 String origin, String destination) {

        String correlationId = UUID.randomUUID().toString();
        LOG.info(String.format("[%s] Creating booking for passenger=%s route=%s→%s",
                correlationId, passengerId, origin, destination));

        Booking b = new Booking();
        b.setBookingRef("BKG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        b.setPassengerId(passengerId);
        b.setPassengerName(passengerName);
        b.setOrigin(origin.toUpperCase());
        b.setDestination(destination.toUpperCase());
        b.setFlightNumber("LB" + (100 + new Random().nextInt(900)));
        b.setDepartureTime(Instant.now().plus(1, ChronoUnit.DAYS));
        b.setArrivalTime(Instant.now().plus(1, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        b.setSeatClass(SEAT_CLASS[new Random().nextInt(SEAT_CLASS.length)]);
        b.setSeatNumber((char) ('A' + new Random().nextInt(6)) + String.valueOf(1 + new Random().nextInt(30)));
        b.setFare(BigDecimal.valueOf(100 + new Random().nextInt(900)));
        b.setCurrency("USD");
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        b.setBookedAt(Instant.now());
        b.setCorrelationId(correlationId);

        bookingRepository.save(b);

        LOG.info(String.format("[%s] Booking %s CONFIRMED flight=%s seat=%s %s fare=%s",
                correlationId, b.getBookingRef(), b.getFlightNumber(),
                b.getSeatClass(), b.getSeatNumber(), b.getFare()));

        return b;
    }

    @Timed(name = "booking.lookup.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to look up a booking by reference")
    public Optional<Booking> getBooking(String bookingRef) {
        return bookingRepository.findByRef(bookingRef);
    }

    @Timed(name = "booking.list.time", unit = MetricUnits.MILLISECONDS,
            description = "Time to list bookings for a passenger")
    public List<Booking> listBookings(String passengerId) {
        return bookingRepository.findByPassenger(passengerId);
    }

    /**
     * Generates a random booking using seeded passenger and airport data.
     * Used by the load generator to produce realistic traffic.
     */
    public Booking createRandomBooking() {
        String pid = "PAX-" + String.format("%04d", new Random().nextInt(1000));
        String name = "Passenger-" + pid;
        String origin      = AIRPORTS[new Random().nextInt(AIRPORTS.length)];
        String destination = AIRPORTS[new Random().nextInt(AIRPORTS.length)];
        if (origin.equals(destination)) {
            destination = AIRPORTS[(Arrays.asList(AIRPORTS).indexOf(origin) + 1) % AIRPORTS.length];
        }
        return createBooking(pid, name, origin, destination);
    }
}
