package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "reservation")
public class ReservationEntity
    extends EventSourcedEntity<ReservationEntity.State, ReservationEntity.Event> {

  private static final Logger log = LoggerFactory.getLogger(ReservationEntity.class);

  private final String entityId;

  public ReservationEntity(EventSourcedEntityContext context) {
    this.entityId = context.entityId();
  }

  public Effect<Done> create(Command.Create command) {
    if (currentState().status() != Status.NOT_CREATED) {
      return effects().reply(Done.done());
    }
    log.info(
        "[reservation] bookingId={} slotId={} action=create pending-participant-requests",
        entityId,
        command.slotId());
    return effects()
        .persistAll(
            List.of(
                new Event.StudentRequestedTimeslot(command.slotId(), command.studentId()),
                new Event.InstructorRequestedTimeslot(command.slotId(), command.instructorId()),
                new Event.AircraftRequestedTimeslot(command.slotId(), command.aircraftId())))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> confirm() {
    if (currentState().status() == Status.CONFIRMED) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED) {
      return effects().error("reservation not created");
    }
    log.info("[reservation] bookingId={} action=confirm", entityId);
    return effects().persist(new Event.Confirmed()).thenReply(__ -> Done.done());
  }

  public Effect<Done> cancel(Command.Cancel command) {
    if (currentState().status() == Status.CANCELED) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED) {
      return effects().error("reservation not created");
    }
    log.info("[reservation] bookingId={} action=cancel reason={}", entityId, command.reason());
    return effects()
        .persistAll(
            List.of(
                new Event.Canceled(command.reason()),
                new Event.StudentReservationCanceled(),
                new Event.InstructorReservationCanceled(),
                new Event.AircraftReservationCanceled()))
        .thenReply(__ -> Done.done());
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  public Effect<Done> acceptRequest(Command.AcceptRequest command) {
    if (currentState().status() == Status.NOT_CREATED || currentState().status() == Status.CANCELED) {
      return effects().reply(Done.done());
    }
    if (isAlreadyHandled(command.participantType(), true)) {
      return effects().reply(Done.done());
    }
    return effects()
        .persist(
            switch (command.participantType()) {
              case STUDENT -> new Event.StudentRequestAccepted();
              case INSTRUCTOR -> new Event.InstructorRequestAccepted();
              case AIRCRAFT -> new Event.AircraftRequestAccepted();
            })
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> rejectRequest(Command.RejectRequest command) {
    if (currentState().status() == Status.NOT_CREATED || currentState().status() == Status.CANCELED) {
      return effects().reply(Done.done());
    }
    if (isAlreadyHandled(command.participantType(), false)) {
      return effects().reply(Done.done());
    }
    return effects()
        .persist(
            switch (command.participantType()) {
              case STUDENT -> new Event.StudentRequestRejected(command.reason());
              case INSTRUCTOR -> new Event.InstructorRequestRejected(command.reason());
              case AIRCRAFT -> new Event.AircraftRequestRejected(command.reason());
            })
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordSlotReadinessMet(Command.RecordSlotReadinessMet ignored) {
    if (currentState().slotParticipantsAvailabilityConfirmed()) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED || currentState().status() == Status.CANCELED) {
      return effects().reply(Done.done());
    }
    return effects()
        .persist(new Event.SlotParticipantAvailabilityConfirmed())
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordStudentUnavailableCompensation(
      Command.RecordStudentUnavailableCompensation ignored) {
    if (currentState().instructorReservationCanceled() && currentState().aircraftReservationCanceled()) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED) {
      return effects().reply(Done.done());
    }
    return effects()
        .persistAll(
            List.of(
                new Event.InstructorReservationCanceled(), new Event.AircraftReservationCanceled()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordInstructorUnavailableCompensation(
      Command.RecordInstructorUnavailableCompensation ignored) {
    if (currentState().studentReservationCanceled()) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED) {
      return effects().reply(Done.done());
    }
    if (currentState().aircraftReservationCanceled()) {
      return effects().persist(new Event.StudentReservationCanceled()).thenReply(__ -> Done.done());
    }
    return effects()
        .persistAll(
            List.of(new Event.StudentReservationCanceled(), new Event.AircraftReservationCanceled()))
        .thenReply(__ -> Done.done());
  }

  public Effect<Done> recordAircraftUnavailableCompensation(
      Command.RecordAircraftUnavailableCompensation ignored) {
    if (currentState().studentReservationCanceled() && currentState().instructorReservationCanceled()) {
      return effects().reply(Done.done());
    }
    if (currentState().status() == Status.NOT_CREATED) {
      return effects().reply(Done.done());
    }
    if (currentState().studentReservationCanceled()) {
      return effects().persist(new Event.InstructorReservationCanceled()).thenReply(__ -> Done.done());
    }
    if (currentState().instructorReservationCanceled()) {
      return effects().persist(new Event.StudentReservationCanceled()).thenReply(__ -> Done.done());
    }
    return effects()
        .persistAll(
            List.of(new Event.StudentReservationCanceled(), new Event.InstructorReservationCanceled()))
        .thenReply(__ -> Done.done());
  }

  @Override
  public State emptyState() {
    return new State(
        entityId,
        null,
        null,
        null,
        null,
        Status.NOT_CREATED,
        null,
        RequestStatus.UNKNOWN,
        RequestStatus.UNKNOWN,
        RequestStatus.UNKNOWN,
        false,
        false,
        false,
        false);
  }

  @Override
  public State applyEvent(Event event) {
    return switch (event) {
      case Event.StudentRequestedTimeslot e ->
          new State(
              entityId,
              e.slotId(),
              e.studentId(),
              currentState().aircraftId(),
              currentState().instructorId(),
              Status.PENDING,
              null,
              RequestStatus.PENDING,
              currentState().instructorRequest(),
              currentState().aircraftRequest(),
              currentState().slotParticipantsAvailabilityConfirmed(),
              currentState().instructorReservationCanceled(),
              currentState().aircraftReservationCanceled(),
              currentState().studentReservationCanceled());
      case Event.InstructorRequestedTimeslot e ->
          new State(
              entityId,
              e.slotId(),
              currentState().studentId(),
              currentState().aircraftId(),
              e.instructorId(),
              Status.PENDING,
              null,
              currentState().studentRequest(),
              RequestStatus.PENDING,
              currentState().aircraftRequest(),
              currentState().slotParticipantsAvailabilityConfirmed(),
              currentState().instructorReservationCanceled(),
              currentState().aircraftReservationCanceled(),
              currentState().studentReservationCanceled());
      case Event.AircraftRequestedTimeslot e ->
          new State(
              entityId,
              e.slotId(),
              currentState().studentId(),
              e.aircraftId(),
              currentState().instructorId(),
              Status.PENDING,
              null,
              currentState().studentRequest(),
              currentState().instructorRequest(),
              RequestStatus.PENDING,
              currentState().slotParticipantsAvailabilityConfirmed(),
              currentState().instructorReservationCanceled(),
              currentState().aircraftReservationCanceled(),
              currentState().studentReservationCanceled());
      case Event.StudentRequestAccepted __ -> currentState().withStudentRequest(RequestStatus.ACCEPTED);
      case Event.InstructorRequestAccepted __ ->
          currentState().withInstructorRequest(RequestStatus.ACCEPTED);
      case Event.AircraftRequestAccepted __ -> currentState().withAircraftRequest(RequestStatus.ACCEPTED);
      case Event.StudentRequestRejected e ->
          currentState().withStudentRequest(RequestStatus.REJECTED).withReason(e.reason());
      case Event.InstructorRequestRejected e ->
          currentState().withInstructorRequest(RequestStatus.REJECTED).withReason(e.reason());
      case Event.AircraftRequestRejected e ->
          currentState().withAircraftRequest(RequestStatus.REJECTED).withReason(e.reason());
      case Event.Confirmed __ -> currentState().withStatus(Status.CONFIRMED);
      case Event.Canceled e -> currentState().withStatus(Status.CANCELED).withReason(e.reason());
      case Event.SlotParticipantAvailabilityConfirmed __ ->
          currentState().withSlotParticipantsAvailabilityConfirmed(true);
      case Event.InstructorReservationCanceled __ ->
          currentState().withInstructorReservationCanceled(true);
      case Event.AircraftReservationCanceled __ ->
          currentState().withAircraftReservationCanceled(true);
      case Event.StudentReservationCanceled __ ->
          currentState().withStudentReservationCanceled(true);
    };
  }

  public enum Status {
    NOT_CREATED,
    PENDING,
    CONFIRMED,
    CANCELED
  }

  public enum RequestStatus {
    UNKNOWN,
    PENDING,
    ACCEPTED,
    REJECTED
  }

  public record State(
      String bookingId,
      String slotId,
      String studentId,
      String aircraftId,
      String instructorId,
      Status status,
      String reason,
      RequestStatus studentRequest,
      RequestStatus instructorRequest,
      RequestStatus aircraftRequest,
      boolean slotParticipantsAvailabilityConfirmed,
      boolean instructorReservationCanceled,
      boolean aircraftReservationCanceled,
      boolean studentReservationCanceled) {
    public State withStatus(Status newStatus) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          newStatus,
          reason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withReason(String newReason) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          newReason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withStudentRequest(RequestStatus requestStatus) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          requestStatus,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withInstructorRequest(RequestStatus requestStatus) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          requestStatus,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withAircraftRequest(RequestStatus requestStatus) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          instructorRequest,
          requestStatus,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withSlotParticipantsAvailabilityConfirmed(boolean value) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          value,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withInstructorReservationCanceled(boolean value) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          value,
          aircraftReservationCanceled,
          studentReservationCanceled);
    }

    public State withAircraftReservationCanceled(boolean value) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          value,
          studentReservationCanceled);
    }

    public State withStudentReservationCanceled(boolean value) {
      return new State(
          bookingId,
          slotId,
          studentId,
          aircraftId,
          instructorId,
          status,
          reason,
          studentRequest,
          instructorRequest,
          aircraftRequest,
          slotParticipantsAvailabilityConfirmed,
          instructorReservationCanceled,
          aircraftReservationCanceled,
          value);
    }
  }

  public sealed interface Command {
    record Create(String slotId, String studentId, String aircraftId, String instructorId)
        implements Command {}

    record Cancel(String reason) implements Command {}

    record AcceptRequest(io.example.domain.Participant.ParticipantType participantType)
        implements Command {}

    record RejectRequest(io.example.domain.Participant.ParticipantType participantType, String reason)
        implements Command {}

    record RecordSlotReadinessMet() implements Command {}

    record RecordStudentUnavailableCompensation() implements Command {}

    record RecordInstructorUnavailableCompensation() implements Command {}

    record RecordAircraftUnavailableCompensation() implements Command {}
  }

  public sealed interface Event {
    @TypeName("student-wants-timeslot")
    record StudentRequestedTimeslot(String slotId, String studentId) implements Event {}

    @TypeName("instructor-wants-timeslot")
    record InstructorRequestedTimeslot(String slotId, String instructorId) implements Event {}

    @TypeName("aircraft-wants-timeslot")
    record AircraftRequestedTimeslot(String slotId, String aircraftId)
        implements Event {}

    @TypeName("student-request-accepted")
    record StudentRequestAccepted() implements Event {}

    @TypeName("student-request-rejected")
    record StudentRequestRejected(String reason) implements Event {}

    @TypeName("instructor-request-accepted")
    record InstructorRequestAccepted() implements Event {}

    @TypeName("instructor-request-rejected")
    record InstructorRequestRejected(String reason) implements Event {}

    @TypeName("aircraft-request-accepted")
    record AircraftRequestAccepted() implements Event {}

    @TypeName("aircraft-request-rejected")
    record AircraftRequestRejected(String reason) implements Event {}

    @TypeName("reservation-confirmed")
    record Confirmed() implements Event {}

    @TypeName("reservation-canceled")
    record Canceled(String reason) implements Event {}

    @TypeName("slot-participant-availability-confirmed")
    record SlotParticipantAvailabilityConfirmed() implements Event {}

    @TypeName("cancel-instructor-reservation")
    record InstructorReservationCanceled() implements Event {}

    @TypeName("cancel-aircraft-reservation")
    record AircraftReservationCanceled() implements Event {}

    @TypeName("cancel-student-reservation")
    record StudentReservationCanceled() implements Event {}
  }

  private boolean isAlreadyHandled(
      io.example.domain.Participant.ParticipantType participantType, boolean accepted) {
    RequestStatus current =
        switch (participantType) {
          case STUDENT -> currentState().studentRequest();
          case INSTRUCTOR -> currentState().instructorRequest();
          case AIRCRAFT -> currentState().aircraftRequest();
        };
    return accepted ? current == RequestStatus.ACCEPTED : current == RequestStatus.REJECTED;
  }
}
