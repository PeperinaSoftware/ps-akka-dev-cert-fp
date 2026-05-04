package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.example.domain.Participant.ParticipantType;

@Component(id = "participant-slot")
public class ParticipantSlotEntity
                extends EventSourcedEntity<ParticipantSlotEntity.State, ParticipantSlotEntity.Event> {

        public Effect<Done> unmarkAvailable(Commands.UnmarkAvailable cmd) {
                if (isDuplicate(cmd.sequenceNumber())) {
                        return effects().reply(Done.done());
                }
                return effects()
                        .persist(new Event.UnmarkedAvailable(
                                        cmd.slotId(), cmd.participantId(), cmd.participantType(), cmd.sequenceNumber()))
                        .thenReply(newState -> Done.done());
        }

        public Effect<Done> markAvailable(Commands.MarkAvailable cmd) {
                if (isDuplicate(cmd.sequenceNumber())) {
                        return effects().reply(Done.done());
                }
                return effects()
                        .persist(new Event.MarkedAvailable(
                                        cmd.slotId(), cmd.participantId(), cmd.participantType(), cmd.sequenceNumber()))
                        .thenReply(newState -> Done.done());
        }

        public Effect<Done> book(Commands.Book cmd) {
                if (isDuplicate(cmd.sequenceNumber())) {
                        return effects().reply(Done.done());
                }
                return effects()
                        .persist(new Event.Booked(
                                        cmd.slotId(),
                                        cmd.participantId(),
                                        cmd.participantType(),
                                        cmd.bookingId(),
                                        cmd.sequenceNumber()))
                        .thenReply(newState -> Done.done());
        }

        public Effect<Done> cancel(Commands.Cancel cmd) {
                var st = currentState();
                if (st != null && "canceled".equals(st.status())) {
                        return effects().reply(Done.done());
                }
                if (isDuplicate(cmd.sequenceNumber())
                        && (st == null || !"booked".equals(st.status()))) {
                        return effects().reply(Done.done());
                }
                return effects()
                        .persist(new Event.Canceled(
                                        cmd.slotId(),
                                        cmd.participantId(),
                                        cmd.participantType(),
                                        cmd.bookingId(),
                                        cmd.sequenceNumber()))
                        .thenReply(newState -> Done.done());
        }

        record State(
                        String slotId,
                        String participantId,
                        ParticipantType participantType,
                        String status,
                        Long lastSequenceNumber) {
        }

        public sealed interface Commands {
                record MarkAvailable(
                                String slotId, String participantId, ParticipantType participantType, Long sequenceNumber)
                                implements Commands {
                }

                record UnmarkAvailable(
                                String slotId, String participantId, ParticipantType participantType, Long sequenceNumber)
                                implements Commands {
                }

                record Book(
                                String slotId,
                                String participantId,
                                ParticipantType participantType,
                                String bookingId,
                                Long sequenceNumber)
                                implements Commands {
                }

                record Cancel(
                                String slotId,
                                String participantId,
                                ParticipantType participantType,
                                String bookingId,
                                Long sequenceNumber)
                                implements Commands {
                }
        }

        public sealed interface Event {
                @TypeName("marked-available")
                record MarkedAvailable(
                                String slotId, String participantId, ParticipantType participantType, Long sequenceNumber)
                                implements Event {
                }

                @TypeName("unmarked-available")
                record UnmarkedAvailable(
                                String slotId, String participantId, ParticipantType participantType, Long sequenceNumber)
                                implements Event {
                }

                @TypeName("participant-booked")
                record Booked(
                                String slotId,
                                String participantId,
                                ParticipantType participantType,
                                String bookingId,
                                Long sequenceNumber)
                                implements Event {
                }

                @TypeName("participant-canceled")
                record Canceled(
                                String slotId,
                                String participantId,
                                ParticipantType participantType,
                                String bookingId,
                                Long sequenceNumber)
                                implements Event {
                }
        }

        @Override
        public State applyEvent(Event event) {
                return switch (event) {
                        case Event.MarkedAvailable e -> new State(
                                        e.slotId(), e.participantId(), e.participantType(), "available", e.sequenceNumber());
                        case Event.UnmarkedAvailable e -> new State(
                                        e.slotId(), e.participantId(), e.participantType(), "unmarked", e.sequenceNumber());
                        case Event.Booked e -> new State(
                                        e.slotId(), e.participantId(), e.participantType(), "booked", e.sequenceNumber());
                        case Event.Canceled e -> new State(
                                        e.slotId(), e.participantId(), e.participantType(), "canceled", e.sequenceNumber());
                };
        }

        @Override
        public State emptyState() {
                return new State(null, null, null, null, null);
        }

        private boolean isDuplicate(Long sequenceNumber) {
                Long current = currentState().lastSequenceNumber();
                return sequenceNumber != null && current != null && sequenceNumber <= current;
        }

}
