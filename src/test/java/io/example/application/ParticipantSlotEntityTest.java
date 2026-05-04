package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.domain.Participant;
import org.junit.jupiter.api.Test;

class ParticipantSlotEntityTest {

  private static final String PARTICIPANT_SLOT_ID = "2026-12-10-10-alice";

  @Test
  void shouldIgnoreDuplicatedSequenceNumbers() {
    var testKit = EventSourcedTestKit.of(PARTICIPANT_SLOT_ID, ParticipantSlotEntity::new);

    var firstResult =
        testKit
            .method(ParticipantSlotEntity::markAvailable)
            .invoke(
                new ParticipantSlotEntity.Commands.MarkAvailable(
                    "2026-12-10-10", "alice", Participant.ParticipantType.STUDENT, 10L));

    assertThat(firstResult.getReply()).isEqualTo(Done.done());
    assertThat(firstResult.didPersistEvents()).isTrue();

    var duplicateResult =
        testKit
            .method(ParticipantSlotEntity::markAvailable)
            .invoke(
                new ParticipantSlotEntity.Commands.MarkAvailable(
                    "2026-12-10-10", "alice", Participant.ParticipantType.STUDENT, 10L));

    assertThat(duplicateResult.getReply()).isEqualTo(Done.done());
    assertThat(duplicateResult.didPersistEvents()).isFalse();
  }
}
