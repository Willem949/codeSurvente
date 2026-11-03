package com.wk.flashsale.booking;

/**
 * Résultat d'une demande de pré-réservation.
 *
 * @param reservation la pré-réservation créée ou retrouvée, null dans les autres cas
 */
public record HoldOutcome(Status status, Reservation reservation) {

    public enum Status {
        /** Place bloquée, nouvelle pré-réservation. */
        CREATED,
        /** Même clé d'idempotence déjà vue : on renvoie la réservation existante, sans en créer une seconde. */
        REPLAYED,
        SOLD_OUT,
        /** File d'attente active et ticket absent, pas encore admis ou déjà utilisé. */
        NOT_ADMITTED,
        /** Clé déjà utilisée pour un autre utilisateur ou un autre événement : erreur du client. */
        KEY_REUSED
    }

    static HoldOutcome created(Reservation reservation) {
        return new HoldOutcome(Status.CREATED, reservation);
    }

    static HoldOutcome replayed(Reservation reservation) {
        return new HoldOutcome(Status.REPLAYED, reservation);
    }

    static HoldOutcome of(Status status) {
        return new HoldOutcome(status, null);
    }
}
