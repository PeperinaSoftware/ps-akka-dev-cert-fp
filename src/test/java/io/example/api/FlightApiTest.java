package io.example.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class FlightApiTest extends TestKitSupport {

  private final TestModelProvider weatherModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(io.example.application.FlightConditionsAgent.class, weatherModel);
  }

  private StatusCode bookingPostStatus(String slotId, FlightEndpoint.BookingRequest body) {
    return httpClient.POST("/flight/bookings/" + slotId).withRequestBody(body).invoke().status();
  }

  private void pollUntilConfirmed(String bookingId) {
    Awaitility.await()
        .atMost(120, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var r =
                  httpClient
                      .GET("/flight/bookings/" + bookingId)
                      .responseBodyAs(String.class)
                      .invoke();
              assertThat(r.status()).isEqualTo(StatusCodes.OK);
              assertThat(r.body()).contains("\"workflowStatus\":\"CONFIRMED\"");
            });
  }

  private String pollUntilTerminalBody(String bookingId) {
    final String[] body = {""};
    Awaitility.await()
        .atMost(120, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              var r =
                  httpClient
                      .GET("/flight/bookings/" + bookingId)
                      .responseBodyAs(String.class)
                      .invoke();
              assertThat(r.status()).isEqualTo(StatusCodes.OK);
              body[0] = r.body();
              assertThat(body[0]).contains("\"terminal\":true");
            });
    return body[0];
  }

  @Test
  void shouldRejectInvalidSlotFormat() {
    var response =
        httpClient
            .POST("/flight/availability/not-a-slot")
            .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
            .responseBodyAs(String.class);

    assertThatThrownBy(response::invoke)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("400 Bad Request")
        .hasMessageContaining("slotId must follow format");
  }

  @Test
  void shouldMarkAvailabilityAndReturnSlotStateFromApi() {
    String slotId = "2027-12-10-10";

    var markResponse =
        httpClient
            .POST("/flight/availability/" + slotId)
            .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
            .invoke();

    assertThat(markResponse.status()).isEqualTo(StatusCodes.OK);

    var getResponse =
        httpClient.GET("/flight/availability/" + slotId).responseBodyAs(String.class).invoke();

    assertThat(getResponse.status()).isEqualTo(StatusCodes.OK);
    assertThat(getResponse.body()).contains("\"id\":\"alice\"");
    assertThat(getResponse.body()).contains("\"participantType\":\"STUDENT\"");
  }

  @Test
  void shouldRejectBookingInPastSlot() {
    var response =
        httpClient
            .POST("/flight/bookings/2020-01-01-10")
            .withRequestBody(
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-past"))
            .responseBodyAs(String.class);

    assertThatThrownBy(response::invoke)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("400 Bad Request")
        .hasMessageContaining("slot must be in the future");
  }

  @Test
  void shouldMapDomainBookingRejectionViaAsyncPoll() {
    String slotId = "2027-12-11-13";
    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2027-12-11-13",
          "meetsRequirements": true,
          "reason": "Clear sky."
        }
        """);

    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("bob", "student"))
        .invoke();

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-not-bookable")))
        .isEqualTo(StatusCodes.ACCEPTED);

    String terminal = pollUntilTerminalBody("bk-not-bookable");
    assertThat(terminal).contains("\"workflowStatus\":\"CANCELED\"");
    assertThat(terminal.toLowerCase()).contains("not available");
  }

  @Test
  void shouldRejectBookingWhenWeatherDoesNotMeetRequirements() {
    String slotId = "2027-12-11-11";
    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2027-12-11-11",
          "meetsRequirements": false,
          "reason": "Storm and poor visibility."
        }
        """);

    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superplane", "aircraft"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superteacher", "instructor"))
        .invoke();

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-bad-weather")))
        .isEqualTo(StatusCodes.ACCEPTED);

    String terminal = pollUntilTerminalBody("bk-bad-weather");
    assertThat(terminal).contains("\"workflowStatus\":\"CANCELED\"");
    assertThat(terminal.toLowerCase()).contains("weather");
  }

  @Test
  void shouldSurfaceInvalidWeatherPayloadViaAsyncPoll() {
    String slotId = "2027-12-11-12";
    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2027-12-11-12",
          "reason": "Payload is missing the decision flag."
        }
        """);

    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superplane", "aircraft"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superteacher", "instructor"))
        .invoke();

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-invalid-payload")))
        .isEqualTo(StatusCodes.ACCEPTED);

    String terminal = pollUntilTerminalBody("bk-invalid-payload");
    assertThat(terminal).contains("\"workflowStatus\":\"CANCELED\"");
    assertThat(terminal.toLowerCase()).contains("payload");
  }

  @Test
  void shouldReturnNotFoundWhenCancelingUnknownReservation() {
    var response =
        httpClient
            .DELETE("/flight/bookings/2028-06-01-10/unknown-booking-id")
            .responseBodyAs(String.class);

    assertThatThrownBy(response::invoke)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("404")
        .hasMessageContaining("Reservation not found");
  }

  @Test
  void shouldRejectCancelWhenSlotIdDoesNotMatchReservation() {
    String slotId = "2028-06-02-10";
    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2028-06-02-10",
          "meetsRequirements": true,
          "reason": "Clear sky."
        }
        """);

    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superplane", "aircraft"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superteacher", "instructor"))
        .invoke();

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-slot-mismatch")))
        .isEqualTo(StatusCodes.ACCEPTED);
    pollUntilConfirmed("bk-slot-mismatch");

    var wrongSlotCancel =
        httpClient
            .DELETE("/flight/bookings/2028-06-03-10/bk-slot-mismatch")
            .responseBodyAs(String.class);

    assertThatThrownBy(wrongSlotCancel::invoke)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("400 Bad Request")
        .hasMessageContaining("does not belong");
  }

  @Test
  void shouldCreateBookingAndCancelIdempotently() {
    String slotId = "2028-06-04-10";
    weatherModel.fixedResponse(
        """
        {
          "timeSlotId": "2028-06-04-10",
          "meetsRequirements": true,
          "reason": "Clear sky."
        }
        """);

    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superplane", "aircraft"))
        .invoke();
    httpClient
        .POST("/flight/availability/" + slotId)
        .withRequestBody(new FlightEndpoint.AvailabilityRequest("superteacher", "instructor"))
        .invoke();

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-cancel-flow")))
        .isEqualTo(StatusCodes.ACCEPTED);
    pollUntilConfirmed("bk-cancel-flow");

    assertThat(
            bookingPostStatus(
                slotId,
                new FlightEndpoint.BookingRequest(
                    "alice", "superplane", "superteacher", "bk-cancel-flow")))
        .isEqualTo(StatusCodes.OK);

    var firstCancel = httpClient.DELETE("/flight/bookings/" + slotId + "/bk-cancel-flow").invoke();
    assertThat(firstCancel.status()).isEqualTo(StatusCodes.OK);

    var secondCancel = httpClient.DELETE("/flight/bookings/" + slotId + "/bk-cancel-flow").invoke();
    assertThat(secondCancel.status()).isEqualTo(StatusCodes.OK);
  }

  @Test
  void shouldTreatDuplicateMarkAvailabilityAsSuccess() {
    String slotId = "2028-06-05-10";
    var body = new FlightEndpoint.AvailabilityRequest("alice", "student");

    assertThat(
            httpClient.POST("/flight/availability/" + slotId).withRequestBody(body).invoke().status())
        .isEqualTo(StatusCodes.OK);
    assertThat(
            httpClient.POST("/flight/availability/" + slotId).withRequestBody(body).invoke().status())
        .isEqualTo(StatusCodes.OK);
  }

  @Test
  void shouldTreatUnmarkWhenNotMarkedAsSuccess() {
    String slotId = "2028-06-06-10";
    var delete =
        httpClient
            .DELETE("/flight/availability/" + slotId)
            .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "student"));

    assertThat(delete.invoke().status()).isEqualTo(StatusCodes.OK);
  }

  @Test
  void shouldRejectInvalidParticipantTypeOnAvailability() {
    var response =
        httpClient
            .POST("/flight/availability/2028-06-07-10")
            .withRequestBody(new FlightEndpoint.AvailabilityRequest("alice", "pilot"))
            .responseBodyAs(String.class);

    assertThatThrownBy(response::invoke)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("400 Bad Request")
        .hasMessageContaining("invalid participant type")
        .hasMessageContaining("student")
        .hasMessageContaining("instructor")
        .hasMessageContaining("aircraft");
  }

  @Test
  void shouldListAllowedStatusesInInvalidStatusError() {
    var response =
        httpClient
            .GET("/flight/slots/alice/unknown-status")
            .responseBodyAs(String.class);

    assertThatThrownBy(response::invoke)
        .hasMessageContaining("400")
        .hasMessageContaining("invalid status")
        .hasMessageContaining("available")
        .hasMessageContaining("booked")
        .hasMessageContaining("canceled");
  }

  @Test
  void shouldReturn404WhenBookingWorkflowNeverStarted() {
    var nf =
        httpClient.GET("/flight/bookings/nonexistent-workflow-xyz").responseBodyAs(String.class);
    assertThatThrownBy(nf::invoke)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("404")
        .hasMessageContaining("not found");
  }
}
