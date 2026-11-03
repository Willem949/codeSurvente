package com.wk.flashsale.stock;

import com.wk.flashsale.event.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Copie du stock dans Redis, partagée par toutes les instances de l'application.
 * Un script Lua s'exécute d'un bloc dans Redis (qui ne traite qu'une commande à la fois) :
 * lire, vérifier et décrémenter y sont donc atomiques, sans verrou, en quelques microsecondes.
 */
@Component
@ConditionalOnProperty(name = "flashsale.redis.enabled", havingValue = "true")
public class RedisStockGate implements StockGate {

    private static final Logger log = LoggerFactory.getLogger(RedisStockGate.class);

    // -1 : compteur absent (à reconstruire depuis Postgres), 0 : épuisé, 1 : une place prise
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then return -1 end
            if tonumber(stock) <= 0 then return 0 end
            redis.call('DECR', KEYS[1])
            return 1
            """, Long.class);

    // On ne rend des places qu'à un compteur existant : absent, il sera reconstruit depuis la base
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final EventRepository eventRepository;

    public RedisStockGate(StringRedisTemplate redis, EventRepository eventRepository) {
        this.redis = redis;
        this.eventRepository = eventRepository;
    }

    static String key(long eventId) {
        // Accolades = "hash tag" : avec Redis Cluster, toutes les clés d'un événement restent sur le même nœud
        return "stock:{" + eventId + "}";
    }

    @Override
    public boolean tryAcquire(long eventId) {
        try {
            Long result = redis.execute(ACQUIRE, List.of(key(eventId)));
            if (result != null && result == -1) {
                loadFromDatabase(eventId);
                result = redis.execute(ACQUIRE, List.of(key(eventId)));
            }
            return result != null && result == 1;
        } catch (DataAccessException redisDown) {
            // Mode dégradé : Redis n'est qu'un accélérateur, on laisse Postgres trancher (plus lent, toujours juste)
            log.warn("Redis indisponible, la réservation passe directement par Postgres : {}", redisDown.getMessage());
            return true;
        }
    }

    private void loadFromDatabase(long eventId) {
        int available = eventRepository.findById(eventId).map(event -> event.getAvailableSeats()).orElse(0);
        // SETNX : si une autre instance l'a chargé entre-temps, on garde sa valeur (déjà décrémentée peut-être)
        redis.opsForValue().setIfAbsent(key(eventId), String.valueOf(available));
    }

    @Override
    public void release(long eventId, int seats) {
        try {
            redis.execute(RELEASE, List.of(key(eventId)), String.valueOf(seats));
        } catch (DataAccessException redisDown) {
            // Le compteur reste trop bas : au pire on refuse des clients alors qu'il reste des places,
            // jamais l'inverse. reset() le reconstruira
            log.warn("Impossible de rendre {} place(s) à Redis pour l'événement {} : {}", seats, eventId, redisDown.getMessage());
        }
    }

    @Override
    public void reset(long eventId) {
        redis.delete(key(eventId));
    }
}
