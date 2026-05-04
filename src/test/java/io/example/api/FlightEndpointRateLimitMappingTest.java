package io.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlightEndpointRateLimitMappingTest {

  @Test
  void shouldDetectRateLimitErrorFromResourceExhaustedMessage() {
    RuntimeException error = new RuntimeException("status: RESOURCE_EXHAUSTED");

    assertThat(FlightEndpoint.isRateLimitedAgentError(error)).isTrue();
  }

  @Test
  void shouldDetectRateLimitErrorFromNestedQuotaExceededMessage() {
    RuntimeException error =
        new RuntimeException("Agent command failed", new RuntimeException("Quota exceeded for metric"));

    assertThat(FlightEndpoint.isRateLimitedAgentError(error)).isTrue();
  }

  @Test
  void shouldNotDetectRateLimitErrorForGenericFailure() {
    RuntimeException error = new RuntimeException("Socket timeout while calling weather service");

    assertThat(FlightEndpoint.isRateLimitedAgentError(error)).isFalse();
  }
}
