-- Étape 5 : pré-réservation avec expiration, et idempotence.

-- Une pré-réservation (PENDING) bloque une place jusqu'à expires_at, puis elle est remise en vente.
-- NULL pour les réservations confirmées d'emblée (stratégies de l'étape 2).
ALTER TABLE reservation ADD COLUMN expires_at TIMESTAMPTZ;

-- Clé générée par le client (un UUID) et renvoyée à chaque nouvel essai : double clic, retry réseau...
-- L'index UNIQUE garantit qu'une même clé ne crée jamais deux réservations, même sur deux instances.
-- Plusieurs NULL restent autorisés (réservations créées sans clé).
ALTER TABLE reservation ADD COLUMN idempotency_key VARCHAR(100);
CREATE UNIQUE INDEX uq_reservation_idempotency_key ON reservation (idempotency_key);

-- Le job d'expiration ne cherche que les PENDING : index partiel, minuscule et toujours à jour
CREATE INDEX idx_reservation_pending_expires_at ON reservation (expires_at) WHERE status = 'PENDING';
