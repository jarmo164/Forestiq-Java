ALTER TABLE weasel_transition_sync_checkpoint
    ADD COLUMN IF NOT EXISTS window_end timestamptz,
    ADD COLUMN IF NOT EXISTS next_page integer;

ALTER TABLE weasel_transition_sync_checkpoint
    DROP CONSTRAINT IF EXISTS ck_weasel_transition_sync_checkpoint_next_page;

ALTER TABLE weasel_transition_sync_checkpoint
    ADD CONSTRAINT ck_weasel_transition_sync_checkpoint_next_page
    CHECK (next_page IS NULL OR next_page >= 1);
