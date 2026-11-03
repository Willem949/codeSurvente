-- Une seule réservation ACTIVE (en attente de paiement ou confirmée) par acheteur et par événement :
-- un bot ne peut pas rafler les places avec des clés d'idempotence différentes.
-- Index partiel : une réservation expirée ne compte plus, l'acheteur peut retenter sa chance.
-- Comme pour le stock, c'est la base qui garantit la règle, même avec plusieurs instances.
CREATE UNIQUE INDEX uq_reservation_active_user
    ON reservation (event_id, user_id)
    WHERE status IN ('PENDING', 'CONFIRMED');
