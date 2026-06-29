package ai.causa.libertyperf.rest;

import ai.causa.libertyperf.model.ApiResponse;
import ai.causa.libertyperf.model.Booking;
import ai.causa.libertyperf.service.BookingService;
import ai.causa.libertyperf.service.ResponsePaddingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * REST resource for airline booking operations.
 */
@Path("/api/bookings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Flight Bookings", description = "Airline booking/reservation operations")
public class BookingResource {

    private static final Logger LOG = Logger.getLogger(BookingResource.class.getName());

    @Inject
    BookingService bookingService;

    @Inject
    ResponsePaddingService paddingService;

    // -------------------------------------------------------------------------
    // Booking endpoints
    // -------------------------------------------------------------------------

    @POST
    @Operation(summary = "Create a new flight booking")
    @APIResponse(responseCode = "201", description = "Booking confirmed")
    @APIResponse(responseCode = "400", description = "Invalid booking request")
    public Response createBooking(BookingRequest request) {
        if (request == null || request.getPassengerId() == null
                || request.getOrigin() == null || request.getDestination() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("passengerId, origin, destination are required",
                            "INVALID_REQUEST", UUID.randomUUID().toString()))
                    .build();
        }

        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        LOG.info(String.format("[%s] POST /api/bookings passenger=%s route=%s→%s",
                correlationId, request.getPassengerId(), request.getOrigin(), request.getDestination()));

        try {
            Booking booking = bookingService.createBooking(
                    request.getPassengerId(),
                    request.getPassengerName() != null ? request.getPassengerName() : request.getPassengerId(),
                    request.getOrigin(),
                    request.getDestination());

            ApiResponse<Booking> resp = ApiResponse.ok(booking, correlationId, System.currentTimeMillis() - start);
            paddingService.pad(resp);
            return Response.status(Response.Status.CREATED)
                    .entity(resp)
                    .build();

        } catch (Exception e) {
            LOG.severe("[" + correlationId + "] Booking failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("Booking processing failed", e.getMessage(), correlationId))
                    .build();
        }
    }

    @GET
    @Path("/{bookingRef}")
    @Operation(summary = "Retrieve a booking by reference number")
    @APIResponse(responseCode = "200", description = "Booking details")
    @APIResponse(responseCode = "404", description = "Booking not found")
    public Response getBooking(@PathParam("bookingRef") String bookingRef) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        Optional<Booking> booking = bookingService.getBooking(bookingRef);
        if (booking.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("Booking not found", "NOT_FOUND", correlationId))
                    .build();
        }

        ApiResponse<Booking> resp = ApiResponse.ok(booking.get(), correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.ok(resp).build();
    }

    @GET
    @Operation(summary = "List bookings for a passenger")
    @APIResponse(responseCode = "200", description = "List of bookings")
    public Response listBookings(@QueryParam("passengerId") String passengerId) {
        if (passengerId == null || passengerId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("passengerId query parameter required",
                            "INVALID_REQUEST", UUID.randomUUID().toString()))
                    .build();
        }
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        List<Booking> bookings = bookingService.listBookings(passengerId);

        ApiResponse<List<Booking>> resp = ApiResponse.ok(bookings, correlationId, System.currentTimeMillis() - start);
        paddingService.pad(resp);
        return Response.ok(resp).build();
    }

    // -------------------------------------------------------------------------
    // Request DTO
    // -------------------------------------------------------------------------

    public static class BookingRequest {
        private String passengerId;
        private String passengerName;
        private String origin;
        private String destination;

        public String getPassengerId()          { return passengerId; }
        public void setPassengerId(String v)    { this.passengerId = v; }

        public String getPassengerName()        { return passengerName; }
        public void setPassengerName(String v)  { this.passengerName = v; }

        public String getOrigin()               { return origin; }
        public void setOrigin(String v)         { this.origin = v; }

        public String getDestination()          { return destination; }
        public void setDestination(String v)    { this.destination = v; }
    }
}
