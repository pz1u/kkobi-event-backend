ALTER TABLE event_sessions
    MODIFY duration_seconds INT NOT NULL DEFAULT 186;

UPDATE event_sessions
SET duration_seconds = 186
WHERE duration_seconds IN (150, 208)
  AND status IN ('WAITING', 'FINISHED');
