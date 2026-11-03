package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.booking.ReservationRepository;
import com.wk.flashsale.event.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La question du journal : "si on ajoute synchronized sur book() de la version naïve, le test passe-t-il ?"
 * Réponse en trois tests : ça dépend d'OÙ on le met, et de toute façon ça ne tient pas à plusieurs instances.
 */
@Import(SynchronizedBookingTest.Config.class)
class SynchronizedBookingTest extends AbstractBookingConcurrencyTest {

    /** Le réflexe : synchronized directement sur la méthode @Transactional. */
    static class SynchronizedNaiveBookingStrategy extends NaiveBookingStrategy {

        SynchronizedNaiveBookingStrategy(EventRepository eventRepository, ReservationRepository reservationRepository) {
            super(eventRepository, reservationRepository);
        }

        @Override
        @Transactional
        public synchronized BookingResult book(long eventId, String userId) {
            return super.book(eventId, userId);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        SynchronizedNaiveBookingStrategy synchronizedNaive(EventRepository eventRepository,
                                                           ReservationRepository reservationRepository) {
            return new SynchronizedNaiveBookingStrategy(eventRepository, reservationRepository);
        }
    }

    /** synchronized AUTOUR de l'appel transactionnel : le verrou n'est rendu qu'après le commit. */
    static BookingStrategy lockedAround(BookingStrategy transactional, Object jvmLock) {
        return (eventId, userId) -> {
            synchronized (jvmLock) {
                return transactional.book(eventId, userId);
            }
        };
    }

    @Autowired
    private SynchronizedNaiveBookingStrategy synchronizedNaive;

    @Autowired
    private NaiveBookingStrategy naive;

    @Test
    void synchronized_sur_la_methode_transactionnelle_survend_encore() throws Exception {
        // Le proxy Spring ouvre la transaction, PUIS entre dans la méthode synchronized ; il en sort
        // (verrou rendu) AVANT de commiter. Le thread suivant lit le stock pendant ce commit : valeur périmée
        Outcome outcome = runConcurrentBookings(synchronizedNaive);

        assertThat(outcome.reservationCount())
                .as("le verrou est rendu avant le commit : survente quand même")
                .isGreaterThan(SEATS);
    }

    @Test
    void synchronized_autour_de_la_transaction_marche_sur_une_seule_instance() throws Exception {
        // Le verrou couvre ouverture, travail ET commit : les réservations passent vraiment une par une
        assertSoldExactlyAllSeats(runConcurrentBookings(lockedAround(naive, new Object())));
    }

    @Test
    void mais_avec_deux_instances_chacune_a_son_propre_verrou_et_ca_survend() throws Exception {
        // Deux JVM = deux objets verrou qui s'ignorent. La base, elle, est partagée
        BookingStrategy instanceA = lockedAround(naive, new Object());
        BookingStrategy instanceB = lockedAround(naive, new Object());
        // Le load balancer envoie chaque acheteur sur l'une ou l'autre
        BookingStrategy loadBalancer = (eventId, userId) ->
                (Math.floorMod(userId.hashCode(), 2) == 0 ? instanceA : instanceB).book(eventId, userId);

        Outcome outcome = runConcurrentBookings(loadBalancer);

        assertThat(outcome.reservationCount())
                .as("synchronized ne protège qu'une JVM : à deux instances, la survente revient")
                .isGreaterThan(SEATS);
    }
}
