package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.domain.Participant;
import org.junit.jupiter.api.Test;

class BookingSlotEntityTest {

  private static final String SLOT_ID = "2026-12-10-10";

  @Test
  void shouldBookWithThreeEventsAndHandleDuplicateBookingIdempotently() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);

    markAvailable(
        testKit, new Participant("alice", Participant.ParticipantType.STUDENT));
    markAvailable(
        testKit, new Participant("superplane", Participant.ParticipantType.AIRCRAFT));
    markAvailable(
        testKit, new Participant("superteacher", Participant.ParticipantType.INSTRUCTOR));

    var firstBooking =
        testKit
            .method(BookingSlotEntity::bookSlot)
            .invoke(
                new BookingSlotEntity.Command.BookReservation(
                    "alice", "superplane", "superteacher", "booking-1"));

    assertThat(firstBooking.getReply()).isEqualTo(Done.done());
    assertThat(firstBooking.didPersistEvents()).isTrue();
    assertThat(firstBooking.getAllEvents()).hasSize(3);

    var duplicateBooking =
        testKit
            .method(BookingSlotEntity::bookSlot)
            .invoke(
                new BookingSlotEntity.Command.BookReservation(
                    "alice", "superplane", "superteacher", "booking-1"));

    assertThat(duplicateBooking.getReply()).isEqualTo(Done.done());
    assertThat(duplicateBooking.didPersistEvents()).isFalse();
  }

  @Test
  void shouldCancelBookingIdempotently() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);

    markAvailable(
        testKit, new Participant("alice", Participant.ParticipantType.STUDENT));
    markAvailable(
        testKit, new Participant("superplane", Participant.ParticipantType.AIRCRAFT));
    markAvailable(
        testKit, new Participant("superteacher", Participant.ParticipantType.INSTRUCTOR));

    testKit
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new BookingSlotEntity.Command.BookReservation(
                "alice", "superplane", "superteacher", "booking-2"));

    var firstCancel = testKit.method(BookingSlotEntity::cancelBooking).invoke("booking-2");
    assertThat(firstCancel.getReply()).isEqualTo(Done.done());
    assertThat(firstCancel.didPersistEvents()).isTrue();
    assertThat(firstCancel.getAllEvents()).hasSize(3);

    var duplicateCancel = testKit.method(BookingSlotEntity::cancelBooking).invoke("booking-2");
    assertThat(duplicateCancel.getReply()).isEqualTo(Done.done());
    assertThat(duplicateCancel.didPersistEvents()).isFalse();
  }

  @Test
  void shouldReturnTypedDomainErrorWhenSlotIsNotBookable() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);

    var result =
        testKit
            .method(BookingSlotEntity::bookSlot)
            .invoke(
                new BookingSlotEntity.Command.BookReservation(
                    "alice", "superplane", "superteacher", "booking-error"));

    assertThat(result.isError()).isTrue();
    assertThat(result.getError()).contains("Booking rejected for slot");
  }

  @Test
  void shouldNotPersistWhenMarkingSameParticipantAvailableTwice() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);
    var p = new Participant("alice", Participant.ParticipantType.STUDENT);

    var first = testKit.method(BookingSlotEntity::markSlotAvailable).invoke(new BookingSlotEntity.Command.MarkSlotAvailable(p));
    assertThat(first.didPersistEvents()).isTrue();

    var second =
        testKit.method(BookingSlotEntity::markSlotAvailable).invoke(new BookingSlotEntity.Command.MarkSlotAvailable(p));
    assertThat(second.getReply()).isEqualTo(Done.done());
    assertThat(second.didPersistEvents()).isFalse();
  }

  @Test
  void shouldNotPersistWhenUnmarkingParticipantWhoWasNotMarked() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);
    var p = new Participant("alice", Participant.ParticipantType.STUDENT);

    var result =
        testKit
            .method(BookingSlotEntity::unmarkSlotAvailable)
            .invoke(new BookingSlotEntity.Command.UnmarkSlotAvailable(p));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isFalse();
  }

  @Test
  void shouldCancelTimeSlotWithOnlyAvailabilityMarks() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);
    markAvailable(testKit, new Participant("alice", Participant.ParticipantType.STUDENT));
    markAvailable(testKit, new Participant("superplane", Participant.ParticipantType.AIRCRAFT));
    markAvailable(testKit, new Participant("superteacher", Participant.ParticipantType.INSTRUCTOR));

    var result =
        testKit
            .method(BookingSlotEntity::cancelTimeSlot)
            .invoke(
                new BookingSlotEntity.Command.CancelTimeSlot(
                    "no-booking", "alice", "superplane", "superteacher"));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isTrue();
    assertThat(result.getAllEvents()).hasSize(3);
  }

  @Test
  void shouldCancelTimeSlotAfterBookingEmittingParticipantCanceledEvents() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);
    markAvailable(testKit, new Participant("alice", Participant.ParticipantType.STUDENT));
    markAvailable(testKit, new Participant("superplane", Participant.ParticipantType.AIRCRAFT));
    markAvailable(testKit, new Participant("superteacher", Participant.ParticipantType.INSTRUCTOR));

    testKit
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new BookingSlotEntity.Command.BookReservation(
                "alice", "superplane", "superteacher", "booking-cancel-ts"));

    var result =
        testKit
            .method(BookingSlotEntity::cancelTimeSlot)
            .invoke(
                new BookingSlotEntity.Command.CancelTimeSlot(
                    "booking-cancel-ts", "alice", "superplane", "superteacher"));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isTrue();
    assertThat(result.getAllEvents()).hasSize(3);
  }

  @Test
  void shouldNotPersistCancelTimeSlotWhenNothingToUndo() {
    var testKit = EventSourcedTestKit.of(SLOT_ID, BookingSlotEntity::new);

    var result =
        testKit
            .method(BookingSlotEntity::cancelTimeSlot)
            .invoke(
                new BookingSlotEntity.Command.CancelTimeSlot(
                    "ghost", "alice", "superplane", "superteacher"));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isFalse();
  }

  private static void markAvailable(
      EventSourcedTestKit<?, ?, BookingSlotEntity> testKit, Participant participant) {
    var result =
        testKit
            .method(BookingSlotEntity::markSlotAvailable)
            .invoke(new BookingSlotEntity.Command.MarkSlotAvailable(participant));

    assertThat(result.getReply()).isEqualTo(Done.done());
    assertThat(result.didPersistEvents()).isTrue();
  }
}
