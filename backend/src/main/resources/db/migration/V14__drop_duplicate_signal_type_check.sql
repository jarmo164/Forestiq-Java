-- V10 left a second check constraint name; V13 only widened the original one.
ALTER TABLE data_change_signal DROP CONSTRAINT IF EXISTS data_change_signal_type_check;
