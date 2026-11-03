package com.wk.flashsale.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Appels de préparation et de vérification, hors mesure : remise à zéro avant le tir,
 * lecture de l'état réel de la base après (endpoints du profil "bench").
 */
final class BenchApi {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private BenchApi() {
    }

    static void reset(String baseUrl, long eventId, int seats) {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/bench/events/" + eventId + "/reset?seats=" + seats))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    /** JSON du type {"totalSeats":500,"availableSeats":0,"reservations":500,"oversold":0,"consistent":true} */
    static String check(String baseUrl, long eventId) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/bench/events/" + eventId + "/check")).GET().build());
    }

    private static String send(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(request.uri() + " a répondu " + response.statusCode()
                        + " : l'application tourne-t-elle avec le profil \"bench\" ?");
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Application injoignable sur " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
