package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.example.domain.Participant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class ParticipantSlotsViewTest extends TestKitSupport {

  @Test
  void shouldProjectAvailableBookedAndCanceledStatuses() {
    String slotId = "2027-12-12-09";
    String bookingId = "booking-view-1";

    markAvailable(slotId, "alice", Participant.ParticipantType.STUDENT);
    markAvailable(slotId, "superplane", Participant.ParticipantType.AIRCRAFT);
    markAvailable(slotId, "superteacher", Participant.ParticipantType.INSTRUCTOR);

    Awaitility.await()
        .untilAsserted(
            () -> {
              var available = slotsByStatus("alice", "available");
              assertThat(available.slots()).hasSize(1);
              assertThat(available.slots().get(0).status()).isEqualTo("available");
            });

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new BookingSlotEntity.Command.BookReservation(
                "alice", "superplane", "superteacher", bookingId));

    Awaitility.await()
        .untilAsserted(
            () -> {
              var booked = slotsByStatus("alice", "booked");
              assertThat(booked.slots()).hasSize(1);
              assertThat(booked.slots().get(0).bookingId()).contains(bookingId);
            });

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::cancelBooking)
        .invoke(bookingId);

    Awaitility.await()
        .untilAsserted(
            () -> {
              var canceled = slotsByStatus("alice", "canceled");
              assertThat(canceled.slots()).hasSize(1);
              assertThat(canceled.slots().get(0).bookingId()).contains(bookingId);
            });
  }

  @Test
  void shouldDeleteViewRowWhenAvailabilityIsUnmarked() {
    String slotId = "2027-12-12-10";

    markAvailable(slotId, "alice", Participant.ParticipantType.STUDENT);

    Awaitility.await()
        .untilAsserted(
            () -> {
              var available = slotsByStatus("alice", "available");
              assertThat(available.slots()).hasSize(1);
            });

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::unmarkSlotAvailable)
        .invoke(
            new BookingSlotEntity.Command.UnmarkSlotAvailable(
                new Participant("alice", Participant.ParticipantType.STUDENT)));

    Awaitility.await()
        .untilAsserted(
            () -> {
              var available = slotsByStatus("alice", "available");
              assertThat(available.slots()).isEmpty();
            });
  }

  private void markAvailable(String slotId, String participantId, Participant.ParticipantType type) {
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new BookingSlotEntity.Command.MarkSlotAvailable(new Participant(participantId, type)));
  }

  private ParticipantSlotsView.SlotList slotsByStatus(String participantId, String status) {
    return componentClient
        .forView()
        .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
        .invoke(new ParticipantSlotsView.ParticipantStatusInput(participantId, status));
  }
}
