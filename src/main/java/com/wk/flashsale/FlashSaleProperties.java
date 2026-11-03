package com.wk.flashsale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Réglages propres à l'application (préfixe "flashsale" dans application.yml).
 */
@ConfigurationProperties("flashsale")
public record FlashSaleProperties(
        @DefaultValue Redis redis,
        @DefaultValue Hold hold,
        @DefaultValue Queue queue) {

    /** Étape 4 : Redis filtre les demandes avant Postgres. Désactivé, tout passe par la base. */
    public record Redis(@DefaultValue("false") boolean enabled) {
    }

    /** Étape 5 : durée de vie d'une pré-réservation non payée. */
    public record Hold(
            @DefaultValue("PT10M") Duration ttl,
            @DefaultValue("PT5S") Duration expiryCheckInterval) {
    }

    /** Étape 6 : file d'attente virtuelle (nécessite Redis). */
    public record Queue(
            @DefaultValue("false") boolean enabled,
            // Débit d'entrée dans la salle de vente : N personnes admises à chaque tick
            @DefaultValue("50") int admissionsPerTick,
            @DefaultValue("PT1S") Duration tickInterval,
            @DefaultValue("PT1H") Duration tokenTtl) {
    }
}
