-- Étape 2 : le stock ne peut plus devenir négatif, quel que soit le code qui écrit.
-- La version naïve survend toujours (elle écrit des valeurs positives mais périmées),
-- la contrainte ne bloque que les décrémentations qui passeraient sous zéro.
ALTER TABLE event
    ADD CONSTRAINT chk_event_available_seats_non_negative CHECK (available_seats >= 0);
