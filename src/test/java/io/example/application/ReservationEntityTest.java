package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

class ReservationEntityTest {

  private static final String BOOKING_ID = "booking-es-1";

  @Test
  void shouldCreateReservationWithPendingParticipantRequests() {
    var tk = EventSourcedTestKit.of(BOOKING_ID, ReservationEntity::new);

    var result =
        tk.method(ReservationEntity::create)
            .invoke(
                new ReservationEntity.Command.Create(
                    "2027-08-01-10", "student-a", "aircraft-x", "instructor-y"));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isTrue();

    ReservationEntity.State state = tk.method(ReservationEntity::get).invoke().getReply();
    assertThat(state.status()).isEqualTo(ReservationEntity.Status.PENDING);
    assertThat(state.slotId()).isEqualTo("2027-08-01-10");
    assertThat(state.studentRequest()).isEqualTo(ReservationEntity.RequestStatus.PENDING);
    assertThat(state.instructorRequest()).isEqualTo(ReservationEntity.RequestStatus.PENDING);
    assertThat(state.aircraftRequest()).isEqualTo(ReservationEntity.RequestStatus.PENDING);
  }

  @Test
  void shouldBeIdempotentOnDuplicateCreate() {
    var tk = EventSourcedTestKit.of("booking-es-2", ReservationEntity::new);
    var cmd =
        new ReservationEntity.Command.Create("2027-08-02-10", "s", "a", "i");

    assertThat(tk.method(ReservationEntity::create).invoke(cmd).didPersistEvents()).isTrue();
    assertThat(tk.method(ReservationEntity::create).invoke(cmd).didPersistEvents()).isFalse();
  }

  @Test
  void shouldRejectSingleParticipantRequestAndPersistReason() {
    var tk = EventSourcedTestKit.of("booking-es-3", ReservationEntity::new);
    tk.method(ReservationEntity::create)
        .invoke(new ReservationEntity.Command.Create("2027-08-03-10", "s", "a", "i"));

    var reject =
        tk.method(ReservationEntity::rejectRequest)
            .invoke(
                new ReservationEntity.Command.RejectRequest(
                    ParticipantType.STUDENT, "participant conflict"));

    assertThat(reject.getReply()).isEqualTo(Done.done());

    ReservationEntity.State state = tk.method(ReservationEntity::get).invoke().getReply();
    assertThat(state.studentRequest()).isEqualTo(ReservationEntity.RequestStatus.REJECTED);
    assertThat(state.reason()).contains("participant conflict");
  }

  @Test
  void shouldConfirmAfterAcceptingAllParticipants() {
    var tk = EventSourcedTestKit.of("booking-es-4", ReservationEntity::new);
    tk.method(ReservationEntity::create)
        .invoke(new ReservationEntity.Command.Create("2027-08-04-10", "s", "a", "i"));

    tk.method(ReservationEntity::acceptRequest)
        .invoke(new ReservationEntity.Command.AcceptRequest(ParticipantType.STUDENT));
    tk.method(ReservationEntity::acceptRequest)
        .invoke(new ReservationEntity.Command.AcceptRequest(ParticipantType.INSTRUCTOR));
    tk.method(ReservationEntity::acceptRequest)
        .invoke(new ReservationEntity.Command.AcceptRequest(ParticipantType.AIRCRAFT));

    var confirmResult = tk.method(ReservationEntity::confirm).invoke();
    assertThat(confirmResult.getReply()).isEqualTo(Done.done());

    ReservationEntity.State state = tk.method(ReservationEntity::get).invoke().getReply();
    assertThat(state.status()).isEqualTo(ReservationEntity.Status.CONFIRMED);
  }

  @Test
  void shouldCancelAndRemainIdempotent() {
    var tk = EventSourcedTestKit.of("booking-es-5", ReservationEntity::new);
    tk.method(ReservationEntity::create)
        .invoke(new ReservationEntity.Command.Create("2027-08-05-10", "s", "a", "i"));

    var cancelCmd = new ReservationEntity.Command.Cancel("changed plans");
    assertThat(tk.method(ReservationEntity::cancel).invoke(cancelCmd).didPersistEvents()).isTrue();
    ReservationEntity.State canceled = tk.method(ReservationEntity::get).invoke().getReply();
    assertThat(canceled.status()).isEqualTo(ReservationEntity.Status.CANCELED);
    assertThat(canceled.reason()).contains("changed plans");

    assertThat(tk.method(ReservationEntity::cancel).invoke(cancelCmd).didPersistEvents()).isFalse();
  }
}
