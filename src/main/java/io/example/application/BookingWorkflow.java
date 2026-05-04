package io.example.application;

import akka.Done;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import akka.javasdk.workflow.Workflow;
import com.typesafe.config.Config;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-workflow")
public class BookingWorkflow extends Workflow<BookingWorkflow.State> {
  private static final Logger log = LoggerFactory.getLogger(BookingWorkflow.class);

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;
  private final boolean allowSimulatedQuotaFallback;
  private final Duration reservationParticipantEvaluationDeadline;
  private final Workflow.WorkflowSettings workflowSettings;

  public BookingWorkflow(ComponentClient componentClient, TimerScheduler timerScheduler, Config config) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
    var flight = config.getConfig("flight-agent");
    this.allowSimulatedQuotaFallback = flight.getBoolean("allow-simulated-quota-fallback");
    this.reservationParticipantEvaluationDeadline =
        flight.getDuration("reservation-participant-evaluation-deadline");
    this.workflowSettings =
        Workflow.WorkflowSettings.builder()
            .defaultStepTimeout(flight.getDuration("booking-workflow-step-timeout"))
            .build();
  }

  @Override
  public Workflow.WorkflowSettings settings() {
    return workflowSettings;
  }

  public Effect<BookingResult> startBooking(Request request) {
    if (request == null) {
      return effects().error("request is required");
    }
    if (currentState() != null && currentState().status() != WorkflowStatus.NOT_STARTED) {
      return effects().error("booking workflow already started");
    }

    var initialState =
        new State(
            request.slotId(),
            request.bookingId(),
            request.studentId(),
            request.aircraftId(),
            request.instructorId(),
            WorkflowStatus.STARTED,
            "started");

    var input =
        new ReserveInput(
            request.slotId(),
            request.bookingId(),
            request.studentId(),
            request.aircraftId(),
            request.instructorId());

    log.info(
        "[booking.workflow] start bookingId={} slotId={}", request.bookingId(), request.slotId());
    return effects()
        .updateState(initialState)
        .transitionTo(BookingWorkflow::createPendingReservationStep)
        .withInput(input)
        .thenReply(new BookingResult(initialState.status().name(), initialState.reason()));
  }

  public ReadOnlyEffect<State> getState() {
    if (currentState() == null) {
      return effects().error("booking workflow not started");
    }
    return effects().reply(currentState());
  }

  @Override
  public State emptyState() {
    return new State(null, null, null, null, null, WorkflowStatus.NOT_STARTED, "not-started");
  }

  @StepName("create-pending-reservation")
  private StepEffect createPendingReservationStep(ReserveInput input) {
    log.info(
        "[booking.workflow] step=create-pending-reservation bookingId={} slotId={}",
        input.bookingId(),
        input.slotId());
    componentClient
        .forEventSourcedEntity(input.bookingId())
        .method(ReservationEntity::create)
        .invoke(
            new ReservationEntity.Command.Create(
                input.slotId(),
                input.studentId(),
                input.aircraftId(),
                input.instructorId()));

    long deadlineEpochMs =
        java.time.Instant.now().plus(reservationParticipantEvaluationDeadline).toEpochMilli();
    var ticket =
        new BookingReservationPollTimedAction.ReservationEvalPollTicket(
            input.bookingId(), deadlineEpochMs);

    timerScheduler.createSingleTimer(
        BookingReservationPollTimedAction.timerName(input.bookingId()),
        Duration.ofMillis(1),
        componentClient
            .forTimedAction()
            .method(BookingReservationPollTimedAction::tickReservationEval)
            .deferred(ticket));

    return stepEffects().thenPause("await reservation participant evaluation");
  }

  /** Invoked by {@link BookingReservationPollTimedAction} when all three participant requests are accepted. */
  public Effect<Done> participantEvaluationSucceeded() {
    var st = currentState();
    if (st == null || st.bookingId() == null || st.status() != WorkflowStatus.STARTED) {
      return effects().reply(Done.done());
    }
    log.info(
        "[booking.workflow] bookingId={} outcome=ok → validate-weather", st.bookingId());
    return effects()
        .updateState(st.withStatus(WorkflowStatus.RESERVATION_PENDING, "reservation-pending"))
        .transitionTo(BookingWorkflow::validateWeatherStep)
        .withInput(new WeatherValidationInput(st.slotId(), st.bookingId()))
        .thenReply(Done.done());
  }

  /** Invoked by {@link BookingReservationPollTimedAction} on reject, timeout, or read failure path. */
  public Effect<Done> participantEvaluationFailed(String reason) {
    var st = currentState();
    if (st == null || st.bookingId() == null) {
      return effects().reply(Done.done());
    }
    if (st.status() != WorkflowStatus.STARTED) {
      return effects().reply(Done.done());
    }
    String safeReason = reason == null ? "request evaluation failed" : reason;
    log.info("[booking.workflow] cancel bookingId={} reason={}", st.bookingId(), safeReason);
    cancelReservation(st.bookingId(), safeReason);
    return effects()
        .updateState(st.withStatus(WorkflowStatus.CANCELED, safeReason))
        .end()
        .thenReply(Done.done());
  }

  @StepName("validate-weather")
  private StepEffect validateWeatherStep(WeatherValidationInput input) {
    log.info(
        "[booking.workflow] step=validate-weather bookingId={} slotId={}",
        input.bookingId(),
        input.slotId());
    FlightConditionsAgent.ConditionsReport report;
    try {
      report =
          componentClient
              .forAgent()
              .inSession("workflow-" + input.slotId())
              .method(FlightConditionsAgent::query)
              .invoke(input.slotId());
    } catch (RuntimeException ex) {
      if (isRateLimitedAgentError(ex)) {
        if (allowSimulatedQuotaFallback) {
          log.warn(
              "[booking.workflow] step=validate-weather bookingId={} slotId={} outcome=rate-limited using simulated approval (allow-simulated-quota-fallback)",
              input.bookingId(),
              input.slotId());
          report =
              new FlightConditionsAgent.ConditionsReport(
                  input.slotId(),
                  true,
                  "[SIMULATED_QUOTA_FALLBACK] Synthetic approval because Gemini quota was exceeded. For test-only end-to-end flow.");
        } else {
          log.warn(
              "[booking.workflow] step=validate-weather bookingId={} slotId={} outcome=rate-limited",
              input.bookingId(),
              input.slotId());
          cancelReservation(input.bookingId(), "rate-limited");
          return stepEffects()
              .updateState(currentState().withStatus(WorkflowStatus.CANCELED, "rate-limited"))
              .thenEnd();
        }
      } else {
        throw ex;
      }
    }

    if (report == null || report.meetsRequirements() == null) {
      log.warn(
          "[booking.workflow] step=validate-weather bookingId={} outcome=invalid-agent-payload",
          input.bookingId());
      cancelReservation(input.bookingId(), "invalid weather decision payload");
      return stepEffects()
          .updateState(currentState().withStatus(WorkflowStatus.CANCELED, "invalid weather decision payload"))
          .thenEnd();
    }

    if (!report.meetsRequirements()) {
      log.info(
          "[booking.workflow] step=validate-weather bookingId={} outcome=unsafe reason={}",
          input.bookingId(),
          report.reason());
      cancelReservation(input.bookingId(), "weather rejected: " + report.reason());
      return stepEffects()
          .updateState(currentState().withStatus(WorkflowStatus.CANCELED, "weather rejected: " + report.reason()))
          .thenEnd();
    }

    log.info(
        "[booking.workflow] step=validate-weather bookingId={} outcome=safe → book-slot",
        input.bookingId());
    return stepEffects()
        .thenTransitionTo(BookingWorkflow::bookSlotStep)
        .withInput(
            new BookSlotInput(
                currentState().slotId(),
                currentState().bookingId(),
                currentState().studentId(),
                currentState().aircraftId(),
                currentState().instructorId()));
  }

  @StepName("book-slot")
  private StepEffect bookSlotStep(BookSlotInput input) {
    log.info(
        "[booking.workflow] step=book-slot bookingId={} slotId={}",
        input.bookingId(),
        input.slotId());
    try {
      componentClient
          .forEventSourcedEntity(input.slotId())
          .method(BookingSlotEntity::bookSlot)
          .invoke(
              new BookingSlotEntity.Command.BookReservation(
                  input.studentId(), input.aircraftId(), input.instructorId(), input.bookingId()));
    } catch (CommandException ex) {
      log.warn(
          "[booking.workflow] step=book-slot bookingId={} outcome=rejected detail={}",
          input.bookingId(),
          ex.getMessage());
      cancelReservation(input.bookingId(), "slot booking rejected: " + ex.getMessage());
      return stepEffects()
          .updateState(
              currentState().withStatus(WorkflowStatus.CANCELED, "slot booking rejected: " + ex.getMessage()))
          .thenEnd();
    }

    componentClient.forEventSourcedEntity(input.bookingId()).method(ReservationEntity::confirm).invoke();

    log.info(
        "[booking.workflow] step=book-slot bookingId={} slotId={} outcome=confirmed",
        input.bookingId(),
        input.slotId());
    return stepEffects()
        .updateState(currentState().withStatus(WorkflowStatus.CONFIRMED, "booking confirmed"))
        .thenEnd();
  }

  private void cancelReservation(String bookingId, String reason) {
    try {
      componentClient
          .forEventSourcedEntity(bookingId)
          .method(ReservationEntity::cancel)
          .invoke(new ReservationEntity.Command.Cancel(reason));
    } catch (RuntimeException ex) {
      log.warn(
          "[booking.workflow] cancel reservation bookingId={} best-effort failed: {}",
          bookingId,
          ex.getMessage());
    }
  }

  public enum WorkflowStatus {
    NOT_STARTED,
    STARTED,
    RESERVATION_PENDING,
    CONFIRMED,
    CANCELED
  }

  public record Request(
      String slotId, String bookingId, String studentId, String aircraftId, String instructorId) {}

  public record BookingResult(String status, String reason) {}

  public record State(
      String slotId,
      String bookingId,
      String studentId,
      String aircraftId,
      String instructorId,
      WorkflowStatus status,
      String reason) {
    public State withStatus(WorkflowStatus newStatus, String newReason) {
      return new State(slotId, bookingId, studentId, aircraftId, instructorId, newStatus, newReason);
    }
  }

  private record ReserveInput(
      String slotId, String bookingId, String studentId, String aircraftId, String instructorId) {}

  private record WeatherValidationInput(String slotId, String bookingId) {}

  private record BookSlotInput(
      String slotId, String bookingId, String studentId, String aircraftId, String instructorId) {}

  public static boolean isRateLimitedAgentError(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 10) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("resource_exhausted")
            || normalized.contains("rate limit")
            || normalized.contains("quota exceeded")
            || normalized.contains("\"code\": 429")
            || normalized.contains("code\":429")) {
          return true;
        }
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }
}
