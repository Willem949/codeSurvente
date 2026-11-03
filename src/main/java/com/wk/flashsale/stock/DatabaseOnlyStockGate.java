package com.wk.flashsale.stock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sans Redis : le portier laisse tout passer, Postgres décide seul.
 */
@Component
@ConditionalOnProperty(name = "flashsale.redis.enabled", havingValue = "false", matchIfMissing = true)
public class DatabaseOnlyStockGate implements StockGate {

    @Override
    public boolean tryAcquire(long eventId) {
        return true;
    }

    @Override
    public void release(long eventId, int seats) {
        // rien à rendre : le compteur n'existe qu'en base
    }

    @Override
    public void reset(long eventId) {
        // rien à oublier
    }
}
