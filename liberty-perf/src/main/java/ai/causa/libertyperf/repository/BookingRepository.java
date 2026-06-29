package ai.causa.libertyperf.repository;

import ai.causa.libertyperf.model.Booking;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Repository for airline Bookings backed by JDBC.
 *
 * <p>Participates in the same connection-pool chaos as {@link AccountRepository}:
 * connections are optionally leaked to exhaust the pool.
 */
@ApplicationScoped
public class BookingRepository {

    private static final Logger LOG = Logger.getLogger(BookingRepository.class.getName());

    @Resource(lookup = "jdbc/libertyPerfDS")
    DataSource dataSource;

    @ConfigProperty(name = "chaos.db.leak.enabled", defaultValue = "false")
    boolean leakConnections;

    @ConfigProperty(name = "chaos.db.slow.query.ms", defaultValue = "0")
    long slowQueryMs;

    // -------------------------------------------------------------------------
    // Schema bootstrap
    // -------------------------------------------------------------------------

    public void ensureSchema() {
        try (Connection c = dataSource.getConnection();
             Statement s  = c.createStatement()) {

            s.execute("""
                    CREATE TABLE IF NOT EXISTS bookings (
                        booking_ref      VARCHAR(36)    NOT NULL PRIMARY KEY,
                        passenger_id     VARCHAR(36)    NOT NULL,
                        passenger_name   VARCHAR(255)   NOT NULL,
                        origin           VARCHAR(8)     NOT NULL,
                        destination      VARCHAR(8)     NOT NULL,
                        flight_number    VARCHAR(16)    NOT NULL,
                        departure_time   TIMESTAMP      NOT NULL,
                        arrival_time     TIMESTAMP      NOT NULL,
                        seat_class       VARCHAR(16),
                        seat_number      VARCHAR(8),
                        fare             DECIMAL(10, 2) NOT NULL,
                        currency         VARCHAR(3)     NOT NULL DEFAULT 'USD',
                        status           VARCHAR(16)    NOT NULL DEFAULT 'CONFIRMED',
                        booked_at        TIMESTAMP      NOT NULL,
                        correlation_id   VARCHAR(36)
                    )""");

        } catch (SQLException e) {
            LOG.severe("BookingRepository schema init failed: " + e.getMessage());
            throw new RuntimeException("BookingRepository schema init failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Booking operations
    // -------------------------------------------------------------------------

    public Booking save(Booking booking) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO bookings
                    (booking_ref, passenger_id, passenger_name, origin, destination,
                     flight_number, departure_time, arrival_time, seat_class, seat_number,
                     fare, currency, status, booked_at, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, booking.getBookingRef());
            ps.setString(2, booking.getPassengerId());
            ps.setString(3, booking.getPassengerName());
            ps.setString(4, booking.getOrigin());
            ps.setString(5, booking.getDestination());
            ps.setString(6, booking.getFlightNumber());
            ps.setTimestamp(7, Timestamp.from(booking.getDepartureTime()));
            ps.setTimestamp(8, Timestamp.from(booking.getArrivalTime()));
            ps.setString(9, booking.getSeatClass());
            ps.setString(10, booking.getSeatNumber());
            ps.setBigDecimal(11, booking.getFare());
            ps.setString(12, booking.getCurrency());
            ps.setString(13, booking.getStatus().name());
            ps.setTimestamp(14, Timestamp.from(booking.getBookedAt()));
            ps.setString(15, booking.getCorrelationId());
            ps.executeUpdate();
            return booking;
        } catch (SQLException e) {
            LOG.warning("save booking failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            maybeClose(conn);
        }
    }

    public Optional<Booking> findByRef(String bookingRef) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM bookings WHERE booking_ref = ?");
            ps.setString(1, bookingRef);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapBooking(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            LOG.warning("findByRef failed: " + e.getMessage());
            return Optional.empty();
        } finally {
            maybeClose(conn);
        }
    }

    public List<Booking> findByPassenger(String passengerId) {
        simulateSlowQuery();
        Connection conn = acquireConnection();
        List<Booking> result = new ArrayList<>();
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM bookings WHERE passenger_id = ? ORDER BY booked_at DESC");
            ps.setString(1, passengerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(mapBooking(rs));
            }
        } catch (SQLException e) {
            LOG.warning("findByPassenger failed: " + e.getMessage());
        } finally {
            maybeClose(conn);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Connection acquireConnection() {
        try {
            Connection conn = dataSource.getConnection();
            if (leakConnections) {
                LOG.warning("[CHAOS] Booking connection acquired and intentionally NOT returned to pool. " +
                        "Pool exhaustion will occur under sustained load.");
            }
            return conn;
        } catch (SQLException e) {
            LOG.severe("[CHAOS] Failed to acquire DB connection (booking): " + e.getMessage());
            throw new RuntimeException("Could not acquire DB connection", e);
        }
    }

    private void maybeClose(Connection conn) {
        if (!leakConnections && conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOG.warning("Failed to close booking connection: " + e.getMessage());
            }
        }
    }

    private void simulateSlowQuery() {
        if (slowQueryMs > 0) {
            try {
                Thread.sleep(slowQueryMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Booking mapBooking(ResultSet rs) throws SQLException {
        Booking b = new Booking();
        b.setBookingRef(rs.getString("booking_ref"));
        b.setPassengerId(rs.getString("passenger_id"));
        b.setPassengerName(rs.getString("passenger_name"));
        b.setOrigin(rs.getString("origin"));
        b.setDestination(rs.getString("destination"));
        b.setFlightNumber(rs.getString("flight_number"));
        b.setDepartureTime(rs.getTimestamp("departure_time").toInstant());
        b.setArrivalTime(rs.getTimestamp("arrival_time").toInstant());
        b.setSeatClass(rs.getString("seat_class"));
        b.setSeatNumber(rs.getString("seat_number"));
        b.setFare(rs.getBigDecimal("fare"));
        b.setCurrency(rs.getString("currency"));
        b.setStatus(Booking.BookingStatus.valueOf(rs.getString("status")));
        b.setBookedAt(rs.getTimestamp("booked_at").toInstant());
        b.setCorrelationId(rs.getString("correlation_id"));
        return b;
    }
}
