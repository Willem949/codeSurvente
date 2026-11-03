package com.wk.flashsale.booking.strategy;

import com.wk.flashsale.stock.RedisStockGate;
import com.wk.flashsale.stock.StockGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis en première ligne, Postgres en dernier mot.
 * Sur 10 000 clients pour 500 places, Redis répond "complet" aux 9 500 perdants sans toucher
 * à la base : seuls ~500 UPDATE atomiques font la queue sur la ligne chaude.
 */
@Component("redis")
@ConditionalOnProperty(name = "flashsale.redis.enabled", havingValue = "true")
public class RedisBookingStrategy implements BookingStrategy {

    private final StockGate stockGate;
    private final AtomicUpdateBookingStrategy database;

    public RedisBookingStrategy(RedisStockGate stockGate, AtomicUpdateBookingStrategy database) {
        this.stockGate = stockGate;
        this.database = database;
    }

    @Override
    public BookingResult book(long eventId, String userId) {
        if (!stockGate.tryAcquire(eventId)) {
            return BookingResult.SOLD_OUT;
        }
        try {
            // Si Redis s'est trompé (compteur trop haut), Postgres répond SOLD_OUT : pas de survente possible.
            // On ne rend rien à Redis dans ce cas, sa décrémentation le rapproche de la vérité
            return database.book(eventId, userId);
        } catch (RuntimeException databaseFailure) {
            // La transaction a été annulée : la place n'est pas prise, on la rend au compteur
            stockGate.release(eventId, 1);
            throw databaseFailure;
        }
    }
}
