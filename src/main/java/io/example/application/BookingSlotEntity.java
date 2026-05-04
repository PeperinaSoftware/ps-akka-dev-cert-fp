package io.example.application;

import akka.Done;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Timeslot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-slot")
public class BookingSlotEntity extends EventSourcedEntity<Timeslot, BookingEvent> {

    private final String entityId;
    private static final Logger log = LoggerFactory.getLogger(BookingSlotEntity.class);

    /**
     * Command failure for {@code bookSlot}: Akka documents {@link akka.javasdk.CommandException} (or
     * {@code effects().error(String)}) to reject commands without persisting. Subclassing {@code
     * CommandException} and calling {@code super(message)} is normal Java; it does not conflict with
     * {@code EventSourcedEntity} inheritance rules (state still changes only via persisted events).
     */
    public static class SlotNotBookableException extends CommandException {
        public SlotNotBookableException(String slotId) {
            super("Booking rejected for slot " + slotId + ": all required participants must be available");
        }
    }

    public BookingSlotEntity(EventSourcedEntityContext context) {
        this.entityId = context.entityId();
    }

    public Effect<Done> markSlotAvailable(Command.MarkSlotAvailable cmd) {
        if (currentState().isWaiting(cmd.participant().id(), cmd.participant().participantType())) {
            log.info("[booking.slot] slotId={} participantId={} action=mark-available outcome=idempotent", entityId, cmd.participant().id());
            return effects().reply(Done.done());
        }

        BookingEvent.ParticipantMarkedAvailable event = new BookingEvent.ParticipantMarkedAvailable(entityId, cmd.participant().id(), cmd.participant().participantType());
        
        log.info("[booking.slot] slotId={} participantId={} action=mark-available", entityId, cmd.participant().id());
        return effects()
            .persist(event)
            .thenReply(newState -> Done.done());
    }

    public Effect<Done> unmarkSlotAvailable(Command.UnmarkSlotAvailable cmd) {
        if (!currentState().isWaiting(cmd.participant().id(), cmd.participant().participantType())) {
            log.info(
                    "[booking.slot] slotId={} participantId={} action=unmark-available outcome=idempotent",
                    entityId,
                    cmd.participant().id());
            return effects().reply(Done.done());
        }

        log.info("[booking.slot] slotId={} participantId={} action=unmark-available", entityId, cmd.participant().id());
        return effects()
            .persist(new BookingEvent.ParticipantUnmarkedAvailable(entityId, cmd.participant().id(), cmd.participant().participantType()))
            .thenReply(newState -> Done.done());
    }

    public Effect<Done> bookSlot(Command.BookReservation cmd) {
        if (currentState().findBooking(cmd.bookingId()).size() == 3) {
            log.info(
                    "[booking.slot] slotId={} bookingId={} action=book outcome=idempotent",
                    entityId,
                    cmd.bookingId());
            return effects().reply(Done.done());
        }

        if (!currentState().isBookable(cmd.studentId(), cmd.aircraftId(), cmd.instructorId())) {
            log.warn(
                    "[booking.slot] slotId={} bookingId={} action=book outcome=rejected reason=participants-not-available",
                    entityId,
                    cmd.bookingId());
            return effects().error(new SlotNotBookableException(entityId));
        }
 
        log.info(
                "[booking.slot] slotId={} bookingId={} action=book outcome=confirmed studentId={}",
                entityId,
                cmd.bookingId(),
                cmd.studentId());
        var events = List.of(
            new BookingEvent.ParticipantBooked(entityId, cmd.studentId(), Participant.ParticipantType.STUDENT, cmd.bookingId()),
            new BookingEvent.ParticipantBooked(entityId, cmd.aircraftId(), Participant.ParticipantType.AIRCRAFT, cmd.bookingId()),
            new BookingEvent.ParticipantBooked(entityId, cmd.instructorId(), Participant.ParticipantType.INSTRUCTOR, cmd.bookingId())
        );

        return effects()
                .persistAll(events)
                .thenReply(newState -> Done.done());
    }

    public Effect<Done> cancelBooking(String bookingId) {
        List<Timeslot.Booking> bookings = bookingsForCancellation(currentState(), bookingId);
        
        if (bookings.isEmpty()) {
            log.info(
                    "[booking.slot] slotId={} bookingId={} action=cancel outcome=idempotent",
                    entityId,
                    bookingId);
            return effects().reply(Done.done());
        }

        log.info("[booking.slot] slotId={} bookingId={} action=cancel", entityId, bookingId);

        List<BookingEvent.ParticipantCanceled> events = bookings.stream()
                .map(
                        b ->
                                new BookingEvent.ParticipantCanceled(
                                        entityId, b.participant().id(), b.participant().participantType(), bookingId))
                .collect(Collectors.toList());

        return effects().persistAll(events).thenReply(newState -> Done.done());
    }

    public Effect<Done> cancelTimeSlot(Command.CancelTimeSlot cmd) {
        Timeslot slot = currentState();
        var bookingEntries = bookingsForCancellation(slot, cmd.bookingId());
        var bookingParticipants =
                bookingEntries.stream().map(Timeslot.Booking::participant).collect(Collectors.toSet());
        List<BookingEvent> events = new ArrayList<>();
        for (Timeslot.Booking b : bookingEntries) {
            events.add(
                    new BookingEvent.ParticipantCanceled(
                            entityId,
                            b.participant().id(),
                            b.participant().participantType(),
                            cmd.bookingId()));
        }
        addUnmarkIfWaiting(
                events, slot, cmd.studentId(), Participant.ParticipantType.STUDENT, bookingParticipants);
        addUnmarkIfWaiting(
                events, slot, cmd.aircraftId(), Participant.ParticipantType.AIRCRAFT, bookingParticipants);
        addUnmarkIfWaiting(
                events, slot, cmd.instructorId(), Participant.ParticipantType.INSTRUCTOR, bookingParticipants);
        if (events.isEmpty()) {
            log.info(
                    "[booking.slot] slotId={} bookingId={} action=cancel-timeslot outcome=no-op",
                    entityId,
                    cmd.bookingId());
            return effects().reply(Done.done());
        }
        log.info(
                "[booking.slot] slotId={} bookingId={} action=cancel-timeslot events={}",
                entityId,
                cmd.bookingId(),
                events.size());
        return effects().persistAll(events).thenReply(__ -> Done.done());
    }

    /**
     * {@link Timeslot#findBooking} uses a HashSet iteration order — non-deterministic. Downstream CloudEvent
     * sequences follow persist order; a stable emission order avoids asymmetric participant-slot behavior.
     */
    private static List<Timeslot.Booking> bookingsForCancellation(Timeslot slot, String bookingId) {
        return slot.findBooking(bookingId).stream()
                .sorted(
                        Comparator.comparing((Timeslot.Booking b) -> participantTypeStableOrder(b.participant().participantType()))
                                .thenComparing(b -> b.participant().id()))
                .toList();
    }

    private static int participantTypeStableOrder(Participant.ParticipantType t) {
        return switch (t) {
            case STUDENT -> 0;
            case AIRCRAFT -> 1;
            case INSTRUCTOR -> 2;
        };
    }

    /** Unmark only provisional waits; never unmark the three participants of the canceled booking. */
    private void addUnmarkIfWaiting(
            List<BookingEvent> out,
            Timeslot slot,
            String participantId,
            Participant.ParticipantType participantType,
            Set<Participant> cancelingBookingParticipants) {
        if (participantId == null) {
            return;
        }
        if (cancelingBookingParticipants.contains(new Participant(participantId, participantType))) {
            return;
        }
        if (slot.isWaiting(participantId, participantType)) {
            out.add(new BookingEvent.ParticipantUnmarkedAvailable(entityId, participantId, participantType));
        }
    }

    public ReadOnlyEffect<Timeslot> getSlot() {
        return effects().reply(currentState());
    }

    public ReadOnlyEffect<SlotParticipantRequestVerdict> verifyParticipantTimeslotRequest(
            Command.VerifyParticipantTimeslotRequest cmd) {
        Timeslot slot = currentState();
        if (slot.isWaiting(cmd.participantId(), cmd.participantType())) {
            return effects().reply(new SlotParticipantRequestVerdict(true, null));
        }
        String reason =
                cmd.participantType() + " is not available in slot " + entityId;
        return effects().reply(new SlotParticipantRequestVerdict(false, reason));
    }

    public record SlotParticipantRequestVerdict(boolean accepted, String rejectionReason) {}

    @Override
    public Timeslot emptyState() {
        return new Timeslot(new HashSet<>(), new HashSet<>());
    }

    @Override
    public Timeslot applyEvent(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantMarkedAvailable e -> currentState().reserve(e);
            case BookingEvent.ParticipantUnmarkedAvailable e -> currentState().unreserve(e);
            case BookingEvent.ParticipantBooked e -> currentState().book(e);
            case BookingEvent.ParticipantCanceled e -> currentState().cancelBooking(e.bookingId());
        };
    }

    public sealed interface Command {
        record MarkSlotAvailable(Participant participant) implements Command {
        }

        record UnmarkSlotAvailable(Participant participant) implements Command {
        }

        record BookReservation(
                String studentId, String aircraftId, String instructorId, String bookingId)
                implements Command {
        }

        record VerifyParticipantTimeslotRequest(
                String participantId, Participant.ParticipantType participantType)
                implements Command {}

        record CancelTimeSlot(
                String bookingId, String studentId, String aircraftId, String instructorId)
                implements Command {}
    }
}
