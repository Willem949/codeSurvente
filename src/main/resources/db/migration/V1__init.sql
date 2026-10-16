-- Volontairement AUCUNE contrainte CHECK (available_seats >= 0) ni index unique :
-- l'étape 1 doit pouvoir démontrer la survente. Les contraintes seront une des solutions de l'étape 2.

CREATE TABLE event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    total_seats     INTEGER      NOT NULL,
    available_seats INTEGER      NOT NULL,
    -- Utilisée plus tard par le verrou optimiste (@Version)
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE reservation (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT       NOT NULL REFERENCES event (id),
    user_id    VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservation_event_id ON reservation (event_id);
