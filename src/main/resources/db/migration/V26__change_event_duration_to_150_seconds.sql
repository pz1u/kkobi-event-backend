-- 행사 투자 게임 진행 시간을 2분 30초로 변경한다.
-- 진행 중인 세션은 확정된 start_at/end_at과 Tick 계산이 어긋나지 않도록 유지하고,
-- WAITING/FINISHED 세션과 이후 생성되는 세션부터 새 정책을 적용한다.
ALTER TABLE event_sessions
    MODIFY duration_seconds INT NOT NULL DEFAULT 150;

UPDATE event_sessions
SET duration_seconds = 150
WHERE status IN ('WAITING', 'FINISHED');
