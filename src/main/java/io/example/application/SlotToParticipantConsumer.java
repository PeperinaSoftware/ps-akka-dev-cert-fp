package io.example.application;

import akka.javasdk.CommandException;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.example.domain.BookingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-slot-consumer")
@Consume.FromEventSourcedEntity(BookingSlotEntity.class)
public class SlotToParticipantConsumer extends Consumer {

    private final ComponentClient client;
    private static final Logger log = LoggerFactory.getLogger(SlotToParticipantConsumer.class);

    public SlotToParticipantConsumer(ComponentClient client) {
        this.client = client;
    }

    public Effect onEvent(BookingEvent event) {
        String pSlotId = participantSlotId(event);
        Long sequenceNumber = messageContext()
                .metadata()
                .asCloudEvent()
                .sequence()
                .orElse(null);
        log.info(
                "[consumer.booking-slot] event={} participantSlotId={} sourceEntitySeq={}",
                event.getClass().getSimpleName(),
                pSlotId,
                sequenceNumber);
 
        return switch (event) {
            case BookingEvent.ParticipantMarkedAvailable e -> {
                invokeParticipantSlotCommand(
                        pSlotId,
                        "markAvailable",
                        () -> client.forEventSourcedEntity(pSlotId)
                                .method(ParticipantSlotEntity::markAvailable)
                                .invoke(new ParticipantSlotEntity.Commands.MarkAvailable(
                                        e.slotId(), e.participantId(), e.participantType(), sequenceNumber)));
                yield effects().done();
            }

            case BookingEvent.ParticipantUnmarkedAvailable e -> {
                invokeParticipantSlotCommand(
                        pSlotId,
                        "unmarkAvailable",
                        () -> client.forEventSourcedEntity(pSlotId)
                                .method(ParticipantSlotEntity::unmarkAvailable)
                                .invoke(new ParticipantSlotEntity.Commands.UnmarkAvailable(
                                        e.slotId(), e.participantId(), e.participantType(), sequenceNumber)));
                yield effects().done();
            }

            case BookingEvent.ParticipantBooked e -> {
                invokeParticipantSlotCommand(
                        pSlotId,
                        "book",
                        () -> client.forEventSourcedEntity(pSlotId)
                                .method(ParticipantSlotEntity::book)
                                .invoke(new ParticipantSlotEntity.Commands.Book(
                                        e.slotId(), e.participantId(), e.participantType(), e.bookingId(), sequenceNumber)));
                yield effects().done();
            }

            case BookingEvent.ParticipantCanceled e -> {
                invokeParticipantSlotCommand(
                        pSlotId,
                        "cancel",
                        () -> client.forEventSourcedEntity(pSlotId)
                                .method(ParticipantSlotEntity::cancel)
                                .invoke(new ParticipantSlotEntity.Commands.Cancel(
                                        e.slotId(), e.participantId(), e.participantType(), e.bookingId(), sequenceNumber)));
                yield effects().done();
            }
        };
    }

    private void invokeParticipantSlotCommand(String pSlotId, String operation, Runnable action) {
        try {
            action.run();
        } catch (CommandException ce) {
            log.warn(
                    "[consumer.booking-slot] operation={} participantSlotId={} outcome=domain-rejection: {}",
                    operation,
                    pSlotId,
                    ce.getMessage());
        } catch (RuntimeException re) {
            log.error(
                    "[consumer.booking-slot] operation={} participantSlotId={} outcome=transient-will-retry",
                    operation,
                    pSlotId,
                    re);
            throw re;
        }
    }

    private String participantSlotId(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantBooked evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantUnmarkedAvailable evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantMarkedAvailable evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantCanceled evt -> evt.slotId() + "-" + evt.participantId();
        };
    }
}
