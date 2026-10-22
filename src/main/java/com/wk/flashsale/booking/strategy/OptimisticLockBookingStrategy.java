package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.Reservation;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.event.VersionedEvent;
import com.wk.flashsale.event.VersionedEventRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verrou optimiste : on ne bloque personne à la lecture, mais l'écriture échoue si la version
 * a changé entre-temps. On recommence alors dans une NOUVELLE transaction, avec le stock à jour.
 * Sur une ligne aussi disputée, la plupart des tentatives entrent en conflit : c'est le coût à mesurer.
 */
@Component
public class OptimisticLockBookingStrategy implements BookingStrategy {

    // Chaque échec signifie qu'un autre client a réussi : le stock progresse toujours vers 0,
    // la limite évite seulement qu'un client malchanceux tourne indéfiniment
    private static final int MAX_ATTEMPTS = 200;

    private final VersionedEventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionTemplate transactionTemplate;

    public OptimisticLockBookingStrategy(VersionedEventRepository eventRepository,
                                         ReservationRepository reservationRepository,
                                         PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public BookingResult book(long eventId, String userId) {
        // La boucle est HORS transaction : une transaction en conflit est perdue, il faut en ouvrir une autre
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> tryBook(eventId, userId));
            } catch (ObjectOptimisticLockingFailureException conflict) {
                // Quelqu'un a modifié l'événement avant nous : on relit et on réessaie
            }
        }
        return BookingResult.CONFLICT;
    }

    private BookingResult tryBook(long eventId, String userId) {
        VersionedEvent event = eventRepository.findById(eventId).orElseThrow();
        if (event.getAvailableSeats() <= 0) {
            return BookingResult.SOLD_OUT;
        }
        event.setAvailableSeats(event.getAvailableSeats() - 1);
        reservationRepository.save(new Reservation(eventId, userId, ReservationStatus.CONFIRMED));
        // L'UPDATE "... WHERE id = ? AND version = ?" part au commit, à la sortie de execute()
        return BookingResult.SUCCESS;
    }
}
