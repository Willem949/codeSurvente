package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.Reservation;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.event.EventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contrainte CHECK : le code ne vérifie rien, il décrémente. C'est PostgreSQL qui refuse
 * un compteur négatif (migration V3). La règle est dans le schéma, donc elle protège aussi
 * les écritures qui ne passent pas par cette classe.
 */
@Component("check")
public class CheckConstraintBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionTemplate transactionTemplate;

    public CheckConstraintBookingStrategy(EventRepository eventRepository,
                                          ReservationRepository reservationRepository,
                                          PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public BookingResult book(long eventId, String userId) {
        try {
            return transactionTemplate.execute(status -> {
                eventRepository.decrementAvailableSeatsUnchecked(eventId);
                reservationRepository.save(new Reservation(eventId, userId, ReservationStatus.CONFIRMED));
                return BookingResult.SUCCESS;
            });
        } catch (DataIntegrityViolationException stockNegatif) {
            // Attrapée HORS de la transaction : après une erreur, PostgreSQL refuse toute autre
            // requête dans la même transaction, elle ne peut qu'être annulée (ce qui est déjà fait ici)
            return BookingResult.SOLD_OUT;
        }
    }
}
