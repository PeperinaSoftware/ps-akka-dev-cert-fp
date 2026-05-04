package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reliable-timer driven polling for {@link ReservationEntity} participant-request resolution. The
 * workflow stays paused until {@link BookingWorkflow#participantEvaluationSucceeded()} or {@link
 * BookingWorkflow#participantEvaluationFailed(String)} is invoked from here.
 */
@Component(id = "booking-reservation-poll")
public class BookingReservationPollTimedAction extends TimedAction {

  private static final Logger log =
      LoggerFactory.getLogger(BookingReservationPollTimedAction.class);
  static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  private final ComponentClient componentClient;

  public BookingReservationPollTimedAction(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** Timer payload; keep small (Akka timer limit 1 KiB). */
  public record ReservationEvalPollTicket(String bookingId, long deadlineEpochMs) {}

  static String timerName(String bookingId) {
    return "booking-reservation-eval-" + bookingId;
  }

  public Effect tickReservationEval(ReservationEvalPollTicket ticket) {
    if (ticket == null || ticket.bookingId() == null || ticket.bookingId().isBlank()) {
      return effects().done();
    }
    String name = timerName(ticket.bookingId());
    try {
      var reservation =
          componentClient
              .forEventSourcedEntity(ticket.bookingId())
              .method(ReservationEntity::get)
              .invoke();

      boolean allHandled =
          reservation.studentRequest() != ReservationEntity.RequestStatus.PENDING
              && reservation.instructorRequest() != ReservationEntity.RequestStatus.PENDING
              && reservation.aircraftRequest() != ReservationEntity.RequestStatus.PENDING;

      if (allHandled) {
        String reject = rejectionReason(reservation);
        if (reject != null) {
          safeInvokeWorkflowFailed(ticket.bookingId(), reject);
        } else {
          safeInvokeWorkflowSucceeded(ticket.bookingId());
        }
        timers().delete(name);
        return effects().done();
      }

      if (java.time.Instant.now().toEpochMilli() >= ticket.deadlineEpochMs()) {
        safeInvokeWorkflowFailed(ticket.bookingId(), "request evaluation timeout");
        timers().delete(name);
        return effects().done();
      }

      timers()
          .createSingleTimer(
              name,
              POLL_INTERVAL,
              componentClient
                  .forTimedAction()
                  .method(BookingReservationPollTimedAction::tickReservationEval)
                  .deferred(ticket));
      return effects().done();
    } catch (RuntimeException ex) {
      log.warn(
          "[booking.reservation-poll] bookingId={} read failed: {} — scheduling another tick",
          ticket.bookingId(),
          ex.getMessage());
      timers()
          .createSingleTimer(
              name,
              POLL_INTERVAL,
              componentClient
                  .forTimedAction()
                  .method(BookingReservationPollTimedAction::tickReservationEval)
                  .deferred(ticket));
      return effects().done();
    }
  }

  private static String rejectionReason(ReservationEntity.State r) {
    if (r.studentRequest() == ReservationEntity.RequestStatus.REJECTED) {
      return r.reason() == null ? "student is not available" : r.reason();
    }
    if (r.instructorRequest() == ReservationEntity.RequestStatus.REJECTED) {
      return r.reason() == null ? "instructor is not available" : r.reason();
    }
    if (r.aircraftRequest() == ReservationEntity.RequestStatus.REJECTED) {
      return r.reason() == null ? "aircraft is not available" : r.reason();
    }
    return null;
  }

  private void safeInvokeWorkflowSucceeded(String bookingId) {
    try {
      componentClient
          .forWorkflow(bookingId)
          .method(BookingWorkflow::participantEvaluationSucceeded)
          .invoke();
    } catch (RuntimeException ex) {
      log.warn(
          "[booking.reservation-poll] participantEvaluationSucceeded bookingId={} ignored: {}",
          bookingId,
          ex.getMessage());
    }
  }

  private void safeInvokeWorkflowFailed(String bookingId, String reason) {
    try {
      componentClient
          .forWorkflow(bookingId)
          .method(BookingWorkflow::participantEvaluationFailed)
          .invoke(reason);
    } catch (RuntimeException ex) {
      log.warn(
          "[booking.reservation-poll] participantEvaluationFailed bookingId={} ignored: {}",
          bookingId,
          ex.getMessage());
    }
  }
}
