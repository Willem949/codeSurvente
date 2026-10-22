package com.wk.flashsale.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Seconde vue de la table "event", avec la colonne "version" mappée par @Version.
 * Elle sert uniquement au verrou optimiste : mettre @Version sur {@link Event} protégerait
 * aussi la version naïve, qui ne pourrait plus démontrer la survente.
 */
@Entity
@Table(name = "event")
public class VersionedEvent {

    @Id
    private Long id;

    @Column(name = "available_seats", nullable = false)
    private int availableSeats;

    // Hibernate ajoute "AND version = ?" à chaque UPDATE et incrémente la valeur :
    // si une autre transaction est passée entre-temps, 0 ligne est modifiée et une exception est levée
    @Version
    @Column(nullable = false)
    private long version;

    protected VersionedEvent() {
        // requis par JPA
    }

    public Long getId() {
        return id;
    }

    public int getAvailableSeats() {
        return availableSeats;
    }

    public void setAvailableSeats(int availableSeats) {
        this.availableSeats = availableSeats;
    }

    public long getVersion() {
        return version;
    }
}
