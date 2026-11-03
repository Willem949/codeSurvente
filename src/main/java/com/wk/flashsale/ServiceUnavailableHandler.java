package com.wk.flashsale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Redis (ou la base) trop lent ou injoignable : c'est une surcharge passagère, pas un bug du client.
 * 503 + Retry-After dit au client "réessaie dans une seconde", au lieu d'une 500 qui ne dit rien.
 * Avec l'Idempotency-Key, ce nouvel essai est sans risque : il ne peut pas prendre une seconde place.
 */
@RestControllerAdvice
class ServiceUnavailableHandler {

    private static final Logger log = LoggerFactory.getLogger(ServiceUnavailableHandler.class);

    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class, QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> overloaded(RuntimeException e) {
        log.warn("Dépendance indisponible, réponse 503 : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Service momentanément surchargé, réessayez dans un instant"));
    }
}
