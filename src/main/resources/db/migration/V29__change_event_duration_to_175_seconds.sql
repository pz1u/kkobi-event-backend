ALTER TABLE event_sessions
    MODIFY duration_seconds INT NOT NULL DEFAULT 175;

UPDATE event_sessions
SET duration_seconds = 175
WHERE duration_seconds IN (150, 186, 208)
  AND status IN ('WAITING', 'FINISHED');
