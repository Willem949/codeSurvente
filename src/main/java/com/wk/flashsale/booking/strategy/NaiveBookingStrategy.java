package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.Reservation;
import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.booking.ReservationStatus;
import com.wk.flashsale.event.EventRepository;

import com.wk.flashsale.event.Event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Version naïve : lire, vérifier, agir (check-then-act).
 * Volontairement fausse sous concurrence, pour démontrer la survente.
 */
@Component
public class NaiveBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;

    public NaiveBookingStrategy(EventRepository eventRepository, ReservationRepository reservationRepository) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
    }

    @Override
    @Transactional
    public BookingResult book(long eventId, String userId) {
        Event event = eventRepository.findById(eventId).orElseThrow();
         
        if (event.getAvailableSeats()>0){
            event.setAvailableSeats(event.getAvailableSeats()-1);
            reservationRepository.save(new Reservation(eventId, userId, ReservationStatus.CONFIRMED));
            return BookingResult.SUCCESS;
        } 
        return BookingResult.SOLD_OUT;

    }
}
