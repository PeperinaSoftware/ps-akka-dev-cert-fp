package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.annotations.Table;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "view-participant-slots")
public class ParticipantSlotsView extends View {

    private static final Logger log = LoggerFactory.getLogger(ParticipantSlotsView.class);

    @Consume.FromEventSourcedEntity(ParticipantSlotEntity.class)
    @Table("participant_slots")
    public static class ParticipantSlotsViewUpdater extends TableUpdater<SlotRow> {

        public Effect<SlotRow> onEvent(ParticipantSlotEntity.Event event) {
            log.debug(
                    "[view.participant-slots] event={} rowKey={}",
                    event.getClass().getSimpleName(),
                    updateContext().eventSubject().orElse("unknown"));
            return switch (event) {
                case ParticipantSlotEntity.Event.MarkedAvailable e -> 
                    effects().updateRow(new SlotRow(
                        e.slotId(), e.participantId(), e.participantType().name(), Optional.empty(), "available"));
                
                case ParticipantSlotEntity.Event.Booked e -> 
                    effects().updateRow(new SlotRow(
                        e.slotId(), e.participantId(), e.participantType().name(), Optional.of(e.bookingId()), "booked"));
                
                case ParticipantSlotEntity.Event.Canceled e -> 
                    effects().updateRow(new SlotRow(
                        e.slotId(), e.participantId(), e.participantType().name(), Optional.of(e.bookingId()), "canceled"));

                case ParticipantSlotEntity.Event.UnmarkedAvailable e -> 
                    effects().deleteRow();
                
                default -> effects().ignore();
            };
        }
    }

    public record SlotRow(
            String slotId,
            String participantId,
            String participantType,
            Optional<String> bookingId,
            String status) {
    }

    public record ParticipantStatusInput(String participantId, String status) {
    }

    public record SlotList(List<SlotRow> slots) {
    }

    @Query("SELECT * AS slots FROM participant_slots WHERE participantId = :participantId AND status = :status")
    public QueryEffect<SlotList> getSlotsByParticipantAndStatus(ParticipantStatusInput input) {
        return queryResult();
    }
}
