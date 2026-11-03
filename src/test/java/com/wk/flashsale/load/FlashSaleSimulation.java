package com.wk.flashsale.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Étape 3 : 10 000 acheteurs, 500 places, une stratégie. Même tir pour toutes les stratégies.
 * <pre>
 * ./mvnw gatling:test -Dstrategy=atomic -Dusers=10000 -DrampSeconds=10
 * ./mvnw gatling:test -Dstrategy=redis -DbaseUrls=http://127.0.0.1:8081,http://127.0.0.1:8082,http://127.0.0.1:8083
 * </pre>
 * Plusieurs baseUrls : Gatling répartit les utilisateurs virtuels en tourniquet, comme un load balancer.
 */
public class FlashSaleSimulation extends Simulation {

    private static final String[] BASE_URLS = System.getProperty("baseUrls", "http://127.0.0.1:8081").split(",");
    private static final String STRATEGY = System.getProperty("strategy", "atomic");
    private static final int USERS = Integer.getInteger("users", 10_000);
    private static final int SEATS = Integer.getInteger("seats", 500);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final long EVENT_ID = 1;

    // Un identifiant d'acheteur unique par utilisateur virtuel
    private static final AtomicLong NEXT_USER = new AtomicLong();
    private static final Iterator<Map<String, Object>> USER_IDS =
            Stream.generate(() -> Map.<String, Object>of("userId", "user-" + NEXT_USER.incrementAndGet())).iterator();

    // Connexions TCP réutilisées entre utilisateurs virtuels, comme derrière un load balancer.
    // Sans ça, 1 000 nouvelles connexions par seconde saturent la pile TCP de Windows ("Connection refused")
    // et on mesure le système d'exploitation au lieu des stratégies. -DshareConnections=false pour comparer
    private static final boolean SHARE_CONNECTIONS = Boolean.parseBoolean(System.getProperty("shareConnections", "true"));

    private final HttpProtocolBuilder protocol = configure(http
            .baseUrls(BASE_URLS)
            .acceptHeader("application/json"));

    private static HttpProtocolBuilder configure(HttpProtocolBuilder builder) {
        return SHARE_CONNECTIONS ? builder.shareConnections() : builder;
    }

    // 201 = place obtenue, 409 = complet : deux réponses NORMALES. Seuls les 5xx et timeouts comptent en KO
    private final ScenarioBuilder buyers = scenario("Vente flash (" + STRATEGY + ")")
            .feed(USER_IDS)
            .exec(http("réserver")
                    .post("/bench/events/" + EVENT_ID + "/bookings")
                    .queryParam("strategy", STRATEGY)
                    .queryParam("userId", "#{userId}")
                    .check(status().in(201, 409)));

    {
        setUp(buyers.injectOpen(rampUsers(USERS).during(RAMP_SECONDS)))
                .protocols(protocol);
    }

    @Override
    public void before() {
        BenchApi.reset(BASE_URLS[0], EVENT_ID, SEATS);
    }

    @Override
    public void after() {
        // La seule vérité : ce que contient la base une fois le tir terminé
        System.out.println("[CHECK " + STRATEGY + "] " + BenchApi.check(BASE_URLS[0], EVENT_ID));
    }
}
