package com.wk.flashsale.booking;

public enum ReservationStatus {
    // Place bloquée en attendant le paiement, jusqu'à expiresAt
    PENDING,
    CONFIRMED,
    // Non confirmée à temps : la place a été remise en vente
    EXPIRED
}
