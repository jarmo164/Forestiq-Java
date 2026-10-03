ALTER TABLE saved_workbasket
    ADD CONSTRAINT saved_workbasket_organization_id_unique UNIQUE (organization_id, id);

CREATE TABLE saved_workbasket_parcel (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    workbasket_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workbasket_id, parcel_id),
    CONSTRAINT saved_workbasket_parcel_basket_fk
        FOREIGN KEY (organization_id, workbasket_id)
        REFERENCES saved_workbasket (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT saved_workbasket_parcel_parcel_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX saved_workbasket_parcel_lookup_idx
    ON saved_workbasket_parcel (organization_id, parcel_id, workbasket_id);

CREATE TABLE saved_workbasket_owner (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    workbasket_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workbasket_id, owner_id),
    CONSTRAINT saved_workbasket_owner_basket_fk
        FOREIGN KEY (organization_id, workbasket_id)
        REFERENCES saved_workbasket (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT saved_workbasket_owner_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX saved_workbasket_owner_lookup_idx
    ON saved_workbasket_owner (organization_id, owner_id, workbasket_id);
