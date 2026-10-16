package com.wk.flashsale.booking.strategy;

/**
 * Contrat commun à toutes les stratégies anti-survente,
 * pour pouvoir les tester et les benchmarker de la même façon.
 */
public interface BookingStrategy {

    BookingResult book(long eventId, String userId);
}
