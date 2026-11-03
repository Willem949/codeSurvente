package com.wk.flashsale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active les jobs périodiques (expiration des pré-réservations, admissions de la file d'attente).
 * Les tests le coupent pour déclencher ces jobs eux-mêmes, au moment voulu.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flashsale.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
