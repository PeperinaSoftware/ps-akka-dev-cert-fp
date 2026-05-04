package io.example.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.example.application.BookingSlotEntity;
import io.example.application.BookingWorkflow;
import io.example.application.ParticipantSlotsView;
import io.example.application.ParticipantSlotsView.SlotList;
import io.example.application.ReservationEntity;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;

/**
 * HTTP API for availability, bookings (async workflow), and participant slot queries.
 *
 * <p><b>Status codes (happy path)</b>
 *
 * <ul>
 *   <li>{@code GET} read endpoints → 200 OK
 *   <li>{@code POST} mark availability → 200 OK
 *   <li>{@code DELETE} availability / booking → 200 OK (including idempotent repeats)
 *   <li>{@code POST /bookings/{slotId}} → 202 Accepted (async) or 200 OK when already CONFIRMED
 * </ul>
 *
 * <p><b>Error responses</b> use plain UTF-8 text bodies (same string as listed below).
 *
 * <ul>
 *   <li>{@code 400 Bad Request} — validation and business rule rejects on this endpoint (missing fields,
 *       bad slot format, participant type, slot not in the future, slot/booking mismatch, etc.).
 *   <li>{@code 404 Not Found} — unknown booking workflow ({@code GET /bookings/...}), or reservation
 *       / booking aggregate not created when canceling.
 *   <li>{@code 202 Accepted} — booking submission accepted for background processing ({@link
 *       #BookingSubmissionResponse}); poll GET until {@code terminal} is true (see {@link
 *       BookingWorkflowStatus}).
 * </ul>
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/flight")
public class FlightEndpoint extends AbstractHttpEndpoint {
    private final Logger log = LoggerFactory.getLogger(FlightEndpoint.class);
    private static final DateTimeFormatter SLOT_ID_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> ALLOWED_STATUS = Set.of("available", "booked", "canceled");

    /** Stable, machine-checkable bodies for 4xx responses (manual tests and clients rely on substrings). */
    private static final String MSG_SLOT_ID_REQUIRED = "slotId is required";
    private static final String MSG_SLOT_ID_FORMAT = "slotId must follow format YYYY-MM-DD-HH";
    private static final String MSG_BOOKING_BODY_REQUIRED = "request body is required";
    private static final String MSG_BOOKING_FIELDS_REQUIRED =
            "studentId, aircraftId, instructorId and bookingId are required";
    private static final String MSG_SLOT_MUST_BE_FUTURE_PREFIX = "booking rejected: slot must be in the future";
    private static final String MSG_WORKFLOW_START_FAILED_PREFIX = "booking could not start";
    private static final String MSG_CANCEL_FAILED_PREFIX = "cancel rejected";
    private static final String MSG_WORKFLOW_ALREADY_STARTED_PHRASE = "already started";
    private static final String MSG_BOOKING_ID_REQUIRED = "bookingId is required";
    private static final String MSG_WORKFLOW_NOT_FOUND = "Booking workflow not found";
    private static final String MSG_RESERVATION_NOT_FOUND = "Reservation not found";
    private static final String MSG_BOOKING_SLOT_MISMATCH =
            "bookingId does not belong to the given slotId";
    private static final String MSG_PARTICIPANT_ID_REQUIRED = "participantId is required";
    private static final String MSG_STATUS_REQUIRED = "status is required";
    private static final String MSG_STATUS_INVALID =
            "invalid status: expected one of available, booked, canceled";
    private static final String MSG_PARTICIPANT_TYPE_INVALID =
            "invalid participant type: expected student, instructor, or aircraft";
    private static final String MSG_AVAILABILITY_FIELDS_REQUIRED =
            "participantId and participantType are required";

    private final ComponentClient componentClient;

    public FlightEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    /** Response for {@code POST /bookings/{slotId}}: accepted for async processing or already completed. */
    public record BookingSubmissionResponse(String bookingId, String slotId, boolean alreadyConfirmed) {}

    /** Response for {@code GET /bookings/{bookingId}}: poll until {@code terminal} is true. */
    public record BookingWorkflowStatus(
            String bookingId,
            String slotId,
            String workflowStatus,
            String reason,
            boolean terminal,
            boolean confirmed,
            boolean canceled) {}

    @Post("/bookings/{slotId}")
    public HttpResponse createBooking(String slotId, BookingRequest request) {
        LocalDateTime slotDateTime = validateSlotId(slotId);
        validateBookingIsFuture(slotDateTime, slotId);
        validateBookingRequest(request);

        log.info(
                "[flight.http] POST /flight/bookings bookingId={} slotId={} student={} instructor={} aircraft={}",
                request.bookingId(),
                slotId,
                request.studentId(),
                request.instructorId(),
                request.aircraftId());

        try {
            var workflowState =
                    componentClient
                            .forWorkflow(request.bookingId())
                            .method(BookingWorkflow::getState)
                            .invoke();
            if (workflowState.status() == BookingWorkflow.WorkflowStatus.CONFIRMED) {
                log.info(
                        "[flight.http] POST /flight/bookings bookingId={} slotId={} outcome=idempotent-confirmed",
                        request.bookingId(),
                        slotId);
                return HttpResponses.ok(
                        new BookingSubmissionResponse(request.bookingId(), slotId, true));
            }
        } catch (RuntimeException ex) {
            // First submission or cold shard: getState may fail before startBooking materializes state.
            // Proceed to startBooking; do not swallow unexpected errors silently.
            log.debug(
                    "[flight.http] POST /flight/bookings bookingId={} slotId={} idempotency pre-check skipped (no readable workflow state yet)",
                    request.bookingId(),
                    slotId,
                    ex);
        }

        try {
            componentClient
                    .forWorkflow(request.bookingId())
                    .method(BookingWorkflow::startBooking)
                    .invoke(
                            new BookingWorkflow.Request(
                                    slotId,
                                    request.bookingId(),
                                    request.studentId(),
                                    request.aircraftId(),
                                    request.instructorId()));
        } catch (CommandException ex) {
            String err = ex.getMessage() == null ? "" : ex.getMessage();
            if (!err.toLowerCase(Locale.ROOT).contains(MSG_WORKFLOW_ALREADY_STARTED_PHRASE)) {
                log.warn(
                        "[flight.http] POST /flight/bookings bookingId={} slotId={} workflow-start rejected: {}",
                        request.bookingId(),
                        slotId,
                        err);
                throw HttpException.badRequest(workflowStartClientMessage(err));
            }
            log.debug(
                    "[flight.http] POST /flight/bookings bookingId={} slotId={} workflow already in progress",
                    request.bookingId(),
                    slotId);
        }

        log.info(
                "[flight.http] POST /flight/bookings bookingId={} slotId={} accepted async (poll GET /flight/bookings/{})",
                request.bookingId(),
                slotId,
                request.bookingId());

        return HttpResponses.accepted(new BookingSubmissionResponse(request.bookingId(), slotId, false));
    }

    @Get("/bookings/{bookingId}")
    public BookingWorkflowStatus getBookingWorkflowStatus(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            throw HttpException.badRequest(MSG_BOOKING_ID_REQUIRED);
        }
        try {
            var state =
                    componentClient
                            .forWorkflow(bookingId)
                            .method(BookingWorkflow::getState)
                            .invoke();
            if (state.status() == BookingWorkflow.WorkflowStatus.NOT_STARTED
                    && (state.bookingId() == null || state.bookingId().isBlank())) {
                throw HttpException.error(StatusCodes.NOT_FOUND, MSG_WORKFLOW_NOT_FOUND);
            }
            return toStatus(state);
        } catch (RuntimeException ex) {
            var msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("not started")) {
                throw HttpException.error(StatusCodes.NOT_FOUND, MSG_WORKFLOW_NOT_FOUND);
            }
            throw ex;
        }
    }

    private static BookingWorkflowStatus toStatus(BookingWorkflow.State state) {
        var st = state.status();
        boolean confirmed = st == BookingWorkflow.WorkflowStatus.CONFIRMED;
        boolean canceled = st == BookingWorkflow.WorkflowStatus.CANCELED;
        boolean terminal = confirmed || canceled;
        String reason = state.reason() == null ? "" : state.reason();
        return new BookingWorkflowStatus(
                state.bookingId(),
                state.slotId() == null ? "" : state.slotId(),
                st.name(),
                reason,
                terminal,
                confirmed,
                canceled);
    }

    @Delete("/bookings/{slotId}/{bookingId}")
    public HttpResponse cancelBooking(String slotId, String bookingId) {
        validateSlotId(slotId);
        if (bookingId == null || bookingId.isBlank()) {
            throw HttpException.badRequest(MSG_BOOKING_ID_REQUIRED);
        }
        var reservation =
                componentClient
                        .forEventSourcedEntity(bookingId)
                        .method(ReservationEntity::get)
                        .invoke();
        if (reservation.status() == ReservationEntity.Status.NOT_CREATED) {
            throw HttpException.error(StatusCodes.NOT_FOUND, MSG_RESERVATION_NOT_FOUND);
        }
        if (reservation.slotId() != null && !reservation.slotId().equals(slotId)) {
            throw HttpException.badRequest(MSG_BOOKING_SLOT_MISMATCH);
        }
        log.info("[flight.http] DELETE /flight/bookings bookingId={} slotId={}", bookingId, slotId);
        try {
            componentClient
                    .forEventSourcedEntity(bookingId)
                    .method(ReservationEntity::cancel)
                    .invoke(new ReservationEntity.Command.Cancel("cancel requested from endpoint"));
        } catch (CommandException ex) {
            if ("reservation not created".equals(ex.getMessage())) {
                throw HttpException.error(StatusCodes.NOT_FOUND, MSG_RESERVATION_NOT_FOUND);
            }
            throw HttpException.badRequest(cancelReservationClientMessage(ex.getMessage()));
        }
        return HttpResponses.ok();
    }

    @Get("/slots/{participantId}/{status}")
    public SlotList slotsByStatus(String participantId, String status) {
        if (participantId == null || participantId.isBlank()) {
            throw HttpException.badRequest(MSG_PARTICIPANT_ID_REQUIRED);
        }
        if (status == null || status.isBlank()) {
            throw HttpException.badRequest(MSG_STATUS_REQUIRED);
        }
        String normalizedStatus = status.trim().toLowerCase();
        if (!ALLOWED_STATUS.contains(normalizedStatus)) {
            throw HttpException.badRequest(MSG_STATUS_INVALID);
        }

        return componentClient
                .forView()
                .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                .invoke(new ParticipantSlotsView.ParticipantStatusInput(participantId, normalizedStatus));
    }

    @Get("/availability/{slotId}")
    public Timeslot getSlot(String slotId) {
        validateSlotId(slotId);
        return componentClient
                .forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::getSlot)
                .invoke();
    }

    @Post("/availability/{slotId}")
    public HttpResponse markAvailable(String slotId, AvailabilityRequest request) {
        validateSlotId(slotId);
        validateAvailabilityRequest(request);
        ParticipantType participantType;

        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("[flight.http] invalid participantType={}", request.participantType());
            throw HttpException.badRequest(MSG_PARTICIPANT_TYPE_INVALID);
        }

        log.info(
                "[flight.http] POST /flight/availability slotId={} participantId={} type={}",
                slotId,
                request.participantId(),
                participantType);

        var cmd =
                new BookingSlotEntity.Command.MarkSlotAvailable(
                        new Participant(request.participantId(), participantType));
        componentClient
                .forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::markSlotAvailable)
                .invoke(cmd);

        return HttpResponses.ok();
    }

    @Delete("/availability/{slotId}")
    public HttpResponse unmarkAvailable(String slotId, AvailabilityRequest request) {
        validateSlotId(slotId);
        validateAvailabilityRequest(request);
        ParticipantType participantType;
        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("[flight.http] invalid participantType={}", request.participantType());
            throw HttpException.badRequest(MSG_PARTICIPANT_TYPE_INVALID);
        }

        var cmd =
                new BookingSlotEntity.Command.UnmarkSlotAvailable(
                        new Participant(request.participantId(), participantType));
        componentClient
                .forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::unmarkSlotAvailable)
                .invoke(cmd);

        log.info(
                "[flight.http] DELETE /flight/availability slotId={} participantId={} type={}",
                slotId,
                request.participantId(),
                participantType);
        return HttpResponses.ok();
    }

    public record BookingRequest(
            String studentId, String aircraftId, String instructorId, String bookingId) {}

    public record AvailabilityRequest(String participantId, String participantType) {}

    private static String boundedClientMessage(String prefix, String detail) {
        if (detail == null || detail.isBlank()) {
            return prefix;
        }
        String oneLine = detail.trim().replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(0, 197) + "...";
        }
        return prefix + ": " + oneLine;
    }

    private static String workflowStartClientMessage(String detail) {
        return boundedClientMessage(MSG_WORKFLOW_START_FAILED_PREFIX, detail);
    }

    private static String cancelReservationClientMessage(String detail) {
        return boundedClientMessage(MSG_CANCEL_FAILED_PREFIX, detail);
    }

    private LocalDateTime validateSlotId(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            throw HttpException.badRequest(MSG_SLOT_ID_REQUIRED);
        }

        try {
            return LocalDateTime.parse(slotId, SLOT_ID_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw HttpException.badRequest(MSG_SLOT_ID_FORMAT);
        }
    }

    private void validateBookingIsFuture(LocalDateTime slotDateTime, String slotId) {
        LocalDateTime nowByHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        if (!slotDateTime.isAfter(nowByHour)) {
            throw HttpException.badRequest(MSG_SLOT_MUST_BE_FUTURE_PREFIX + " (" + slotId + ")");
        }
    }

    private void validateBookingRequest(BookingRequest request) {
        if (request == null) {
            throw HttpException.badRequest(MSG_BOOKING_BODY_REQUIRED);
        }
        if (isBlank(request.studentId())
                || isBlank(request.aircraftId())
                || isBlank(request.instructorId())
                || isBlank(request.bookingId())) {
            throw HttpException.badRequest(MSG_BOOKING_FIELDS_REQUIRED);
        }
    }

    private void validateAvailabilityRequest(AvailabilityRequest request) {
        if (request == null) {
            throw HttpException.badRequest(MSG_BOOKING_BODY_REQUIRED);
        }
        if (isBlank(request.participantId()) || isBlank(request.participantType())) {
            throw HttpException.badRequest(MSG_AVAILABILITY_FIELDS_REQUIRED);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static boolean isRateLimitedAgentError(Throwable error) {
        try {
            return BookingWorkflow.isRateLimitedAgentError(error);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
