-- 행사의 실제 종료 시각을 저장 (end_at은 예정된 종료 시각, finished_at은 실제 종료 시각)
ALTER TABLE event_sessions
    ADD COLUMN finished_at DATETIME NULL AFTER end_at;


-- 행사 참가자별 최종 결과(자산/수익률/성향)를 1회 확정하여 저장
CREATE TABLE event_game_results (
                                     result_id BIGINT NOT NULL AUTO_INCREMENT,
                                     participant_id BIGINT NOT NULL,
                                     session_id BIGINT NOT NULL,
                                     persona_id BIGINT NOT NULL,

                                     initial_asset BIGINT NOT NULL,
                                     final_asset BIGINT NOT NULL,
                                     return_rate DECIMAL(9,2) NOT NULL,

                                     rt_score DECIMAL(5,2) NOT NULL,
                                     lh_score DECIMAL(5,2) NOT NULL,
                                     rp_score DECIMAL(5,2) NOT NULL,

                                     final_tick INT NOT NULL,
                                     final_price BIGINT NOT NULL,

                                     finished_at DATETIME NOT NULL,
                                     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                     PRIMARY KEY (result_id),

                                     CONSTRAINT fk_event_game_results_participant
                                         FOREIGN KEY (participant_id)
                                             REFERENCES event_participants(participant_id)
                                             ON DELETE CASCADE,

                                     CONSTRAINT fk_event_game_results_session
                                         FOREIGN KEY (session_id)
                                             REFERENCES event_sessions(session_id)
                                             ON DELETE CASCADE,

                                     CONSTRAINT fk_event_game_results_persona
                                         FOREIGN KEY (persona_id)
                                             REFERENCES personas(persona_id),

                                     CONSTRAINT uk_event_game_results_participant
                                         UNIQUE (participant_id),

                                     CONSTRAINT chk_event_game_results_initial_asset
                                         CHECK (initial_asset > 0),

                                     CONSTRAINT chk_event_game_results_final_asset
                                         CHECK (final_asset >= 0),

                                     CONSTRAINT chk_event_game_results_return_rate
                                         CHECK (return_rate >= -100),

                                     CONSTRAINT chk_event_game_results_rt_score
                                         CHECK (rt_score BETWEEN 0 AND 100),

                                     CONSTRAINT chk_event_game_results_lh_score
                                         CHECK (lh_score BETWEEN 0 AND 100),

                                     CONSTRAINT chk_event_game_results_rp_score
                                         CHECK (rp_score BETWEEN 0 AND 100),

                                     CONSTRAINT chk_event_game_results_final_tick
                                         CHECK (final_tick >= 0),

                                     CONSTRAINT chk_event_game_results_final_price
                                         CHECK (final_price > 0)
);
