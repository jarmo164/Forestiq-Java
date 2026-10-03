ALTER TABLE data_change_signal DROP CONSTRAINT data_change_signal_signal_type_check;
ALTER TABLE data_change_signal ADD CONSTRAINT data_change_signal_signal_type_check
    CHECK (signal_type IN ('NEW_FOREST_NOTIFICATION', 'NEW_FOREST_COMPARTMENT', 'DECEASED_OWNER', 'OWNERSHIP_TRANSITION', 'PORTFOLIO_OWNERSHIP_ADDED'));
