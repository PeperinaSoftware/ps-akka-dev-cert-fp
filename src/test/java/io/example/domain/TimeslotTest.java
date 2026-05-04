package io.example.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.example.domain.Participant.ParticipantType;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TimeslotTest {

  @Test
  void shouldReserveBookAndCancelBookingFlow() {
    var slot = new Timeslot(new HashSet<>(), new HashSet<>());

    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "2026-12-11-09", "alice", ParticipantType.STUDENT));
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "2026-12-11-09", "superplane", ParticipantType.AIRCRAFT));
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "2026-12-11-09", "superteacher", ParticipantType.INSTRUCTOR));

    assertThat(slot.isBookable("alice", "superplane", "superteacher")).isTrue();

    slot =
        slot.book(
            new BookingEvent.ParticipantBooked(
                "2026-12-11-09", "alice", ParticipantType.STUDENT, "booking-42"));
    slot =
        slot.book(
            new BookingEvent.ParticipantBooked(
                "2026-12-11-09", "superplane", ParticipantType.AIRCRAFT, "booking-42"));
    slot =
        slot.book(
            new BookingEvent.ParticipantBooked(
                "2026-12-11-09", "superteacher", ParticipantType.INSTRUCTOR, "booking-42"));

    assertThat(slot.bookings()).hasSize(3);
    assertThat(slot.available()).isEmpty();
    assertThat(slot.findBooking("booking-42")).hasSize(3);

    slot = slot.cancelBooking("booking-42");
    assertThat(slot.bookings()).isEmpty();
  }

  @Test
  void shouldUnreserveParticipant() {
    var slot = new Timeslot(new HashSet<>(), new HashSet<>());
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "2026-12-11-10", "alice", ParticipantType.STUDENT));

    assertThat(slot.isWaiting("alice", ParticipantType.STUDENT)).isTrue();

    slot =
        slot.unreserve(
            new BookingEvent.ParticipantUnmarkedAvailable(
                "2026-12-11-10", "alice", ParticipantType.STUDENT));

    assertThat(slot.isWaiting("alice", ParticipantType.STUDENT)).isFalse();
  }
}
