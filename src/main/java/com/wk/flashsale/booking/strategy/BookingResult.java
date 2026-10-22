package com.wk.flashsale.booking.strategy;

public enum BookingResult {
    SUCCESS,
    SOLD_OUT,
    // Verrou optimiste uniquement : trop de conflits d'affilée, le client abandonne
    CONFLICT
}
