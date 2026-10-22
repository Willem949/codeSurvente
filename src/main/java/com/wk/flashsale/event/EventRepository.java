package com.wk.flashsale.event;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Lire, vérifier et décrémenter en UNE seule requête : il n'y a plus d'écart
     * entre la lecture et l'écriture dans lequel une autre transaction pourrait s'intercaler.
     *
     * @return 1 si une place a été prise, 0 s'il n'en restait plus (ou si l'événement n'existe pas)
     */
    @Modifying
    @Query("UPDATE Event e SET e.availableSeats = e.availableSeats - 1 WHERE e.id = :id AND e.availableSeats > 0")
    int decrementAvailableSeats(@Param("id") long id);

    /**
     * SELECT ... FOR UPDATE : la ligne reste verrouillée jusqu'à la fin de la transaction,
     * les autres lecteurs attendent au lieu de lire une valeur bientôt périmée.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") long id);
}
