package com.wk.flashsale.queue;

import com.wk.flashsale.FlashSaleProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File d'attente virtuelle, partagée par toutes les instances via Redis.
 * <p>
 * Principe du ticket de boulangerie : chaque arrivant tire un numéro (INCR, strictement croissant
 * même avec 3 instances : premier arrivé, premier servi). Un curseur "admis jusqu'au n° X" avance
 * de N numéros à chaque tick. On est admis quand son numéro est ≤ X.
 * Tout est en O(1) : aucune liste à parcourir, quelle que soit la taille de la file.
 */
@Component
@ConditionalOnProperty(name = {"flashsale.redis.enabled", "flashsale.queue.enabled"}, havingValue = "true")
public class WaitingRoom {

    private static final String EVENTS_KEY = "queue:events";

    // Tirer un numéro et le rattacher au ticket, en une opération
    private static final RedisScript<Long> JOIN = RedisScript.of("""
            local number = redis.call('INCR', KEYS[1])
            redis.call('SET', KEYS[2], number, 'EX', ARGV[1])
            return number
            """, Long.class);

    // Le verrou NX garantit qu'une seule instance fait avancer le curseur à chaque tick,
    // sinon 3 instances admettraient 3 fois trop de monde. Le curseur ne dépasse jamais le dernier numéro tiré
    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            if not redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[2]) then return -1 end
            local last = tonumber(redis.call('GET', KEYS[3]) or '0')
            local admitted = tonumber(redis.call('GET', KEYS[2]) or '0')
            local nextAdmitted = math.min(admitted + tonumber(ARGV[1]), last)
            redis.call('SET', KEYS[2], nextAdmitted)
            return nextAdmitted
            """, Long.class);

    // Vérifier l'admission et détruire le ticket d'un bloc : un ticket ne sert qu'une fois,
    // même si deux requêtes l'utilisent au même instant (ticket partagé entre amis, double clic)
    private static final RedisScript<Long> CONSUME = RedisScript.of("""
            local number = redis.call('GET', KEYS[1])
            if not number then return -1 end
            local admitted = tonumber(redis.call('GET', KEYS[2]) or '0')
            if tonumber(number) > admitted then return 0 end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final FlashSaleProperties.Queue settings;
    private final Map<Long, AtomicLong> waitingGauges = new ConcurrentHashMap<>();

    public WaitingRoom(StringRedisTemplate redis, MeterRegistry meterRegistry, FlashSaleProperties properties) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.settings = properties.queue();
    }

    // Toutes les clés d'un événement partagent le hash tag {eventId} : un script Lua
    // ne peut toucher que des clés du même nœud avec Redis Cluster
    private static String lastNumberKey(long eventId) {
        return "queue:{" + eventId + "}:last";
    }

    private static String admittedKey(long eventId) {
        return "queue:{" + eventId + "}:admitted";
    }

    private static String tickLockKey(long eventId) {
        return "queue:{" + eventId + "}:tick";
    }

    private static String ticketKey(long eventId, String token) {
        return "queue:{" + eventId + "}:ticket:" + token;
    }

    public QueueTicket join(long eventId) {
        String token = UUID.randomUUID().toString();
        long number = redis.execute(JOIN, List.of(lastNumberKey(eventId), ticketKey(eventId, token)),
                String.valueOf(settings.tokenTtl().toSeconds()));
        redis.opsForSet().add(EVENTS_KEY, String.valueOf(eventId));
        meterRegistry.counter("flashsale.queue.joined").increment();
        return ticket(token, number, admitted(eventId));
    }

    public Optional<QueueTicket> status(long eventId, String token) {
        String number = redis.opsForValue().get(ticketKey(eventId, token));
        if (number == null) {
            return Optional.empty();
        }
        return Optional.of(ticket(token, Long.parseLong(number), admitted(eventId)));
    }

    /** @return true si le ticket était admis : il est alors consommé et ne pourra plus resservir */
    public boolean consumeIfAdmitted(long eventId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long result = redis.execute(CONSUME, List.of(ticketKey(eventId, token), admittedKey(eventId)));
        return result != null && result == 1;
    }

    /** Un tick : fait entrer les N suivants de chaque file. Appelé par chaque instance, une seule agit. */
    public void admitNextBatch() {
        // Un peu plus court que l'intervalle, pour que le tick suivant (sur n'importe quelle instance) passe
        long lockMillis = Math.max(1, settings.tickInterval().toMillis() * 9 / 10);
        Set<String> events = redis.opsForSet().members(EVENTS_KEY);
        if (events == null) {
            return;
        }
        for (String event : events) {
            long eventId = Long.parseLong(event);
            redis.execute(ADMIT, List.of(tickLockKey(eventId), admittedKey(eventId), lastNumberKey(eventId)),
                    String.valueOf(settings.admissionsPerTick()), String.valueOf(lockMillis));
            updateWaitingGauge(eventId);
        }
    }

    private void updateWaitingGauge(long eventId) {
        long last = parse(redis.opsForValue().get(lastNumberKey(eventId)));
        waitingGauges.computeIfAbsent(eventId, id -> meterRegistry.gauge(
                        "flashsale.queue.waiting", List.of(Tag.of("event", String.valueOf(id))),
                        new AtomicLong()))
                .set(Math.max(0, last - admitted(eventId)));
    }

    /** Vide la file d'un événement (benchmarks, tests). */
    public void reset(long eventId) {
        redis.delete(List.of(lastNumberKey(eventId), admittedKey(eventId), tickLockKey(eventId)));
        Set<String> tickets = redis.keys("queue:{" + eventId + "}:ticket:*");
        if (tickets != null && !tickets.isEmpty()) {
            redis.delete(tickets);
        }
    }

    private long admitted(long eventId) {
        return parse(redis.opsForValue().get(admittedKey(eventId)));
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private static QueueTicket ticket(String token, long number, long admitted) {
        return number <= admitted
                ? new QueueTicket(token, QueueTicket.Status.ADMITTED, 0)
                : new QueueTicket(token, QueueTicket.Status.WAITING, number - admitted);
    }
}
