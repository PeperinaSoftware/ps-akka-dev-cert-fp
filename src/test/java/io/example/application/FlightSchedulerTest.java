package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.example.domain.Participant;
import org.junit.jupiter.api.Test;

public class FlightSchedulerTest extends TestKitSupport {

  private final TestModelProvider weatherModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(FlightConditionsAgent.class, weatherModel);
  }

  @Test
  void shouldPropagateBookingLifecycleAndUseMockedAgent() {
    String slotId = "2026-12-10-10";
    String bookingId = "booking-it-1";

    markAvailable(slotId, "alice", Participant.ParticipantType.STUDENT);
    markAvailable(slotId, "superplane", Participant.ParticipantType.AIRCRAFT);
    markAvailable(slotId, "superteacher", Participant.ParticipantType.INSTRUCTOR);

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::bookSlot)
        .invoke(
            new BookingSlotEntity.Command.BookReservation(
                "alice", "superplane", "superteacher", bookingId));

    var bookedSlot =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertThat(bookedSlot.bookings()).hasSize(3);
    assertThat(bookedSlot.available()).isEmpty();

    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::cancelBooking)
        .invoke(bookingId);

    var canceledSlot =
        componentClient
            .forEventSourcedEntity(slotId)
            .method(BookingSlotEntity::getSlot)
            .invoke();
    assertThat(canceledSlot.bookings()).isEmpty();

    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2026-12-10-10",
          "meetsRequirements": true,
          "reason": "Clear sky and light wind."
        }
        """);

    var result =
        componentClient
            .forAgent()
            .inSession("it-agent")
            .method(FlightConditionsAgent::query)
            .invoke("2026-12-10-10");

    assertThat(result.timeSlotId()).isEqualTo("2026-12-10-10");
    assertThat(result.meetsRequirements()).isTrue();
    assertThat(result.reason()).contains("Clear sky");
  }

  private void markAvailable(String slotId, String participantId, Participant.ParticipantType type) {
    componentClient
        .forEventSourcedEntity(slotId)
        .method(BookingSlotEntity::markSlotAvailable)
        .invoke(new BookingSlotEntity.Command.MarkSlotAvailable(new Participant(participantId, type)));
  }
}
