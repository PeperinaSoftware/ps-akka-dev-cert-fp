package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "reservation-to-timeslot-consumer")
@Consume.FromEventSourcedEntity(ReservationEntity.class)
public class ReservationToTimeSlotConsumer extends Consumer {

  private final ComponentClient componentClient;
  private static final Logger log = LoggerFactory.getLogger(ReservationToTimeSlotConsumer.class);

  public ReservationToTimeSlotConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(ReservationEntity.Event event) {
    return switch (event) {
      case ReservationEntity.Event.StudentRequestedTimeslot e -> {
        evaluateRequest(e.slotId(), e.studentId(), ParticipantType.STUDENT);
        yield effects().done();
      }
      case ReservationEntity.Event.InstructorRequestedTimeslot e -> {
        evaluateRequest(e.slotId(), e.instructorId(), ParticipantType.INSTRUCTOR);
        yield effects().done();
      }
      case ReservationEntity.Event.AircraftRequestedTimeslot e -> {
        evaluateRequest(e.slotId(), e.aircraftId(), ParticipantType.AIRCRAFT);
        yield effects().done();
      }
      case ReservationEntity.Event.InstructorRequestAccepted __ -> {
        afterParticipantRequestAccepted(ParticipantType.INSTRUCTOR);
        yield effects().done();
      }
      case ReservationEntity.Event.AircraftRequestAccepted __ -> {
        afterParticipantRequestAccepted(ParticipantType.AIRCRAFT);
        yield effects().done();
      }
      case ReservationEntity.Event.StudentRequestAccepted __ -> {
        afterParticipantRequestAccepted(ParticipantType.STUDENT);
        yield effects().done();
      }
      case ReservationEntity.Event.StudentRequestRejected __ -> {
        onStudentRequestRejected();
        yield effects().done();
      }
      case ReservationEntity.Event.InstructorRequestRejected __ -> {
        onInstructorRequestRejected();
        yield effects().done();
      }
      case ReservationEntity.Event.AircraftRequestRejected __ -> {
        onAircraftRequestRejected();
        yield effects().done();
      }
      case ReservationEntity.Event.StudentReservationCanceled __ -> {
        cancelTimeSlotAfterCompensationEvent();
        yield effects().done();
      }
      case ReservationEntity.Event.InstructorReservationCanceled __ -> {
        cancelTimeSlotAfterCompensationEvent();
        yield effects().done();
      }
      case ReservationEntity.Event.AircraftReservationCanceled __ -> {
        cancelTimeSlotAfterCompensationEvent();
        yield effects().done();
      }
      default -> effects().ignore();
    };
  }

  private void evaluateRequest(String slotId, String participantId, ParticipantType participantType) {
    String reservationId = messageContext().eventSubject().orElseThrow();
    var verdict =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::verifyParticipantTimeslotRequest)
            .invoke(
                new BookingSlotEntity.Command.VerifyParticipantTimeslotRequest(
                    participantId, participantType));

    if (verdict.accepted()) {
      logParticipantVerdict(reservationId, participantId, participantType, true);
      componentClient
          .forEventSourcedEntity(reservationId)
          .method(ReservationEntity::acceptRequest)
          .invoke(new ReservationEntity.Command.AcceptRequest(participantType));
    } else {
      logParticipantVerdict(reservationId, participantId, participantType, false);
      componentClient
          .forEventSourcedEntity(reservationId)
          .method(ReservationEntity::rejectRequest)
          .invoke(
              new ReservationEntity.Command.RejectRequest(
                  participantType, verdict.rejectionReason()));
    }
  }

  private void logParticipantVerdict(
      String reservationId, String participantId, ParticipantType type, boolean accepted) {
    log.info(
        "[consumer.reservation-timeslot] reservationId={} participantId={} role={} slotVerdict={}",
        reservationId,
        participantId,
        type,
        accepted ? "accepted" : "rejected");
  }

  private void afterParticipantRequestAccepted(ParticipantType participantType) {
    String bookingId = messageContext().eventSubject().orElseThrow();
    var res =
        componentClient
            .forEventSourcedEntity(bookingId)
            .method(ReservationEntity::get)
            .invoke();
    if (!shouldSyncSlotAfterAccept(res)) {
      return;
    }
    if (res.slotId() == null) {
      return;
    }
    Participant participant =
        switch (participantType) {
          case STUDENT -> new Participant(res.studentId(), Participant.ParticipantType.STUDENT);
          case INSTRUCTOR -> new Participant(res.instructorId(), Participant.ParticipantType.INSTRUCTOR);
          case AIRCRAFT -> new Participant(res.aircraftId(), Participant.ParticipantType.AIRCRAFT);
        };
    var slot =
        componentClient
            .forEventSourcedEntity(res.slotId())
            .method(BookingSlotEntity::getSlot)
            .invoke();
    // Late delivery of *RequestAccepted after bookSlot would re-add `available` and break cancel + views.
    boolean alreadyBookedForThisReservation =
        slot.findBooking(bookingId).stream().anyMatch(b -> b.participant().equals(participant));
    if (alreadyBookedForThisReservation) {
      log.info(
          "[consumer.reservation-timeslot] bookingId={} slotId={} role={} action=skip-mark participant already booked on slot",
          bookingId,
          res.slotId(),
          participantType);
    } else {
      componentClient
          .forEventSourcedEntity(res.slotId())
          .method(BookingSlotEntity::markSlotAvailable)
          .invoke(new BookingSlotEntity.Command.MarkSlotAvailable(participant));
      slot =
          componentClient
              .forEventSourcedEntity(res.slotId())
              .method(BookingSlotEntity::getSlot)
              .invoke();
    }
    if (slot.isBookable(res.studentId(), res.aircraftId(), res.instructorId())
        && !res.slotParticipantsAvailabilityConfirmed()) {
      log.info(
          "[consumer.reservation-timeslot] bookingId={} slotId={} phase=all-participants-available-on-slot",
          bookingId,
          res.slotId());
      componentClient
          .forEventSourcedEntity(bookingId)
          .method(ReservationEntity::recordSlotReadinessMet)
          .invoke(new ReservationEntity.Command.RecordSlotReadinessMet());
    }
  }

  private boolean shouldSyncSlotAfterAccept(ReservationEntity.State res) {
    if (res.status() == ReservationEntity.Status.CANCELED) {
      return false;
    }
    return res.studentRequest() != ReservationEntity.RequestStatus.REJECTED
        && res.instructorRequest() != ReservationEntity.RequestStatus.REJECTED
        && res.aircraftRequest() != ReservationEntity.RequestStatus.REJECTED;
  }

  private void onInstructorRequestRejected() {
    String bookingId = messageContext().eventSubject().orElseThrow();
    var res =
        componentClient
            .forEventSourcedEntity(bookingId)
            .method(ReservationEntity::get)
            .invoke();
    if (res.slotId() == null) {
      return;
    }
    log.info(
        "[consumer.reservation-timeslot] bookingId={} phase=compensation reason=instructor-unavailable",
        bookingId);
    componentClient
        .forEventSourcedEntity(bookingId)
        .method(ReservationEntity::recordInstructorUnavailableCompensation)
        .invoke(new ReservationEntity.Command.RecordInstructorUnavailableCompensation());
  }

  private void onAircraftRequestRejected() {
    String bookingId = messageContext().eventSubject().orElseThrow();
    var res =
        componentClient
            .forEventSourcedEntity(bookingId)
            .method(ReservationEntity::get)
            .invoke();
    if (res.slotId() == null) {
      return;
    }
    log.info(
        "[consumer.reservation-timeslot] bookingId={} phase=compensation reason=aircraft-unavailable",
        bookingId);
    componentClient
        .forEventSourcedEntity(bookingId)
        .method(ReservationEntity::recordAircraftUnavailableCompensation)
        .invoke(new ReservationEntity.Command.RecordAircraftUnavailableCompensation());
  }

  private void onStudentRequestRejected() {
    String bookingId = messageContext().eventSubject().orElseThrow();
    var res =
        componentClient
            .forEventSourcedEntity(bookingId)
            .method(ReservationEntity::get)
            .invoke();
    if (res.slotId() == null) {
      return;
    }
    log.info(
        "[consumer.reservation-timeslot] bookingId={} phase=compensation reason=student-unavailable",
        bookingId);
    componentClient
        .forEventSourcedEntity(bookingId)
        .method(ReservationEntity::recordStudentUnavailableCompensation)
        .invoke(new ReservationEntity.Command.RecordStudentUnavailableCompensation());
  }

  private void cancelTimeSlotAfterCompensationEvent() {
    String bookingId = messageContext().eventSubject().orElseThrow();
    var res =
        componentClient
            .forEventSourcedEntity(bookingId)
            .method(ReservationEntity::get)
            .invoke();
    if (res.slotId() == null) {
      return;
    }
    log.info(
        "[consumer.reservation-timeslot] bookingId={} slotId={} phase=cancel-timeslot-after-compensation",
        bookingId,
        res.slotId());
    componentClient
        .forEventSourcedEntity(res.slotId())
        .method(BookingSlotEntity::cancelTimeSlot)
        .invoke(
            new BookingSlotEntity.Command.CancelTimeSlot(
                bookingId, res.studentId(), res.aircraftId(), res.instructorId()));
  }

}
