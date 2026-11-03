package com.wk.flashsale.stock;

/**
 * Filtre AVANT la base : répond très vite "plus de place" pour éviter que des milliers
 * de requêtes perdues d'avance fassent la queue sur la ligne de l'événement.
 * <p>
 * Ce n'est PAS lui qui empêche la survente : un "oui" du portier est toujours confirmé
 * par l'UPDATE atomique de Postgres, la seule source de vérité.
 */
public interface StockGate {

    /** @return false si on sait déjà qu'il n'y a plus de place, true si la base doit trancher */
    boolean tryAcquire(long eventId);

    /** Rend des places au portier (réservation annulée, pré-réservation expirée, erreur en base). */
    void release(long eventId, int seats);

    /** Oublie l'état du portier : il sera reconstruit depuis Postgres au prochain appel. */
    void reset(long eventId);
}
