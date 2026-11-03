package com.wk.flashsale.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAsDuring;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Étapes 5 et 6 : le vrai parcours d'un acheteur, sur l'API publique.
 * File d'attente → admis → pré-réservation (avec Idempotency-Key, et un double clic) → paiement ou abandon.
 * <p>
 * L'application doit tourner avec Redis et la file d'attente :
 * {@code --flashsale.redis.enabled=true --flashsale.queue.enabled=true --spring.profiles.active=bench}
 * <pre>./mvnw gatling:test -Dgatling.simulationClass=com.wk.flashsale.load.BuyerJourneySimulation</pre>
 */
public class BuyerJourneySimulation extends Simulation {

    private static final String[] BASE_URLS = System.getProperty("baseUrls", "http://localhost:8080").split(",");
    private static final int USERS = Integer.getInteger("users", 3_000);
    private static final int SEATS = Integer.getInteger("seats", 500);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final long EVENT_ID = 1;

    private static final AtomicLong NEXT_USER = new AtomicLong();
    private static final Iterator<Map<String, Object>> BUYERS = Stream.generate(() -> Map.<String, Object>of(
            "userId", "buyer-" + NEXT_USER.incrementAndGet(),
            // Générée une fois par le client, renvoyée telle quelle à chaque nouvel essai
            "idempotencyKey", UUID.randomUUID().toString())).iterator();

    private final HttpProtocolBuilder protocol = http
            .baseUrls(BASE_URLS)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder journey = scenario("Parcours acheteur")
            .feed(BUYERS)
            .exec(http("entrer dans la file")
                    .post("/events/" + EVENT_ID + "/queue")
                    .check(status().is(201))
                    .check(jsonPath("$.token").saveAs("queueToken"))
                    .check(jsonPath("$.status").saveAs("queueStatus")))
            // Sans ticket, la suite n'a pas de sens (et la boucle d'attente tournerait à vide)
            .exitHereIfFailed()
            // Comme la page "vous êtes 1 234e dans la file", rafraîchie toutes les secondes ;
            // abandon au bout de 2 minutes, comme un vrai acheteur
            .exec(asLongAsDuring(session -> !"ADMITTED".equals(session.getString("queueStatus")), Duration.ofMinutes(2))
                    .on(exec(http("position dans la file")
                                    .get("/events/" + EVENT_ID + "/queue/#{queueToken}")
                                    .check(status().is(200))
                                    .check(jsonPath("$.status").saveAs("queueStatus")))
                            .exitHereIfFailed()
                            .pause(Duration.ofSeconds(1))))
            .exitHereIf(session -> !"ADMITTED".equals(session.getString("queueStatus")))
            .exec(http("bloquer une place")
                    .post("/events/" + EVENT_ID + "/reservations")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .header("X-Queue-Token", "#{queueToken}")
                    .body(StringBody("{\"userId\":\"#{userId}\"}"))
                    .check(status().in(201, 409))
                    .check(jsonPath("$.id").optional().saveAs("reservationId")))
            // Double clic : même clé, le ticket est déjà consommé, la réponse doit être la même réservation
            .exec(http("double clic")
                    .post("/events/" + EVENT_ID + "/reservations")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .header("X-Queue-Token", "#{queueToken}")
                    .body(StringBody("{\"userId\":\"#{userId}\"}"))
                    // 200 = rejeu de la réservation ; 403 = la 1re tentative était "complet", le ticket est consommé
                    .check(status().in(200, 403)))
            .doIf(session -> session.contains("reservationId")).then(
                    randomSwitch().on(
                            // 70 % paient dans les temps, 30 % abandonnent : leur place expirera et sera remise en vente
                            percent(70.0).then(http("payer et confirmer")
                                    .post("/reservations/#{reservationId}/confirm")
                                    .check(status().is(204)))));

    {
        setUp(journey.injectOpen(rampUsers(USERS).during(RAMP_SECONDS)))
                .protocols(protocol)
                .maxDuration(Duration.ofMinutes(5));
    }

    @Override
    public void before() {
        BenchApi.reset(BASE_URLS[0], EVENT_ID, SEATS);
    }

    @Override
    public void after() {
        System.out.println("[CHECK parcours] " + BenchApi.check(BASE_URLS[0], EVENT_ID));
    }
}
