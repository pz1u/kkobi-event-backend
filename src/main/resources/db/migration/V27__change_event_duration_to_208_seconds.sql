ALTER TABLE event_sessions
    MODIFY duration_seconds INT NOT NULL DEFAULT 208;

UPDATE event_sessions
SET duration_seconds = 208
WHERE duration_seconds = 150
  AND status IN ('WAITING', 'FINISHED');
