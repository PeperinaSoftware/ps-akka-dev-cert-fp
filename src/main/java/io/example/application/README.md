# Akka Components (application)

This package now contains the full implementation, not just the initial starter set.

Core components in this module:

* `BookingSlotEntity` - Main slot aggregate (`available` + `bookings`) with idempotent mark/unmark/book/cancel behavior.
* `ParticipantSlotEntity` - Per `slotId-participantId` state used for query-side projection.
* `ParticipantSlotsView` - Query model for participant slots by `participantId` and status.
* `SlotToParticipantConsumer` - Consumes `BookingSlotEntity` events and updates `ParticipantSlotEntity`.
* `ReservationEntity` - Reservation aggregate per `bookingId` (request/accept/reject/confirm/cancel state machine).
* `ReservationToTimeSlotConsumer` - Reacts to reservation events and coordinates slot verify/mark/cancel commands.
* `BookingWorkflow` - Orchestration flow: reservation → evaluation → weather gate → slot booking/confirm or cancel.
* `BookingReservationPollTimedAction` - Timer-based polling that resumes/fails the workflow based on reservation progress.
* `FlightConditionsAgent` - Weather suitability check used by the workflow.

Reference diagrams under [`images/`](../../../../../../images) were used as layout guidance.
