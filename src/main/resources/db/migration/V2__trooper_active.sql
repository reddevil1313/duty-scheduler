-- V2: archive Troopers with logged duty instead of deleting them, so logged
-- sheets stay intact.

ALTER TABLE trooper ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
