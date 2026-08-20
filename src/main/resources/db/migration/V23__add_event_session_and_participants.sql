-- 행사 세션 정보를 저장
CREATE TABLE event_sessions (
                                session_id BIGINT NOT NULL AUTO_INCREMENT,
                                status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
                                scenario_id VARCHAR(20) NOT NULL DEFAULT 'SC001',
                                countdown_started_at DATETIME NULL,
                                start_at DATETIME NULL,
                                end_at DATETIME NULL,
                                duration_seconds INT NOT NULL DEFAULT 180,
                                initial_cash BIGINT NOT NULL DEFAULT 10000000,
                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP,

                                PRIMARY KEY (session_id),

                                CONSTRAINT chk_event_sessions_status
                                    CHECK (status IN ('WAITING', 'COUNTDOWN', 'RUNNING', 'FINISHED')),

                                CONSTRAINT chk_event_sessions_duration_seconds
                                    CHECK (duration_seconds > 0),

                                CONSTRAINT chk_event_sessions_initial_cash
                                    CHECK (initial_cash > 0)
);


-- 행사 참가자 정보를 저장
CREATE TABLE event_participants (
                                     participant_id BIGINT NOT NULL AUTO_INCREMENT,
                                     session_id BIGINT NOT NULL,
                                     nickname VARCHAR(30) NOT NULL,
                                     participant_token VARCHAR(36) NOT NULL,
                                     joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,

                                     PRIMARY KEY (participant_id),

                                     CONSTRAINT fk_event_participants_session
                                         FOREIGN KEY (session_id)
                                             REFERENCES event_sessions(session_id)
                                             ON DELETE CASCADE,

                                     CONSTRAINT uk_event_participants_session_nickname
                                         UNIQUE (session_id, nickname),

                                     CONSTRAINT uk_event_participants_token
                                         UNIQUE (participant_token)
);


-- 행사 1회 운영을 위한 기본 행사 세션 생성
INSERT INTO event_sessions (
    status,
    scenario_id,
    duration_seconds,
    initial_cash
) VALUES (
             'WAITING',
             'SC001',
             180,
             10000000
         );
