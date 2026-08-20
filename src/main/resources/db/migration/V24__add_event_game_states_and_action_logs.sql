-- 행사 참가자별 게임 상태를 저장 (회원용 게임과 분리, participantId 기준)
CREATE TABLE event_game_states (
                                    game_state_id BIGINT NOT NULL AUTO_INCREMENT,
                                    participant_id BIGINT NOT NULL,
                                    session_id BIGINT NOT NULL,
                                    scenario_id VARCHAR(20) NOT NULL,
                                    initial_cash BIGINT NOT NULL,
                                    cash_balance BIGINT NOT NULL,
                                    stock_principal BIGINT NOT NULL DEFAULT 0,
                                    stock_quantity DECIMAL(24,8) NOT NULL DEFAULT 0,
                                    deposit_amount BIGINT NOT NULL DEFAULT 0,
                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,

                                    PRIMARY KEY (game_state_id),

                                    CONSTRAINT fk_event_game_states_participant
                                        FOREIGN KEY (participant_id)
                                            REFERENCES event_participants(participant_id)
                                            ON DELETE CASCADE,

                                    CONSTRAINT fk_event_game_states_session
                                        FOREIGN KEY (session_id)
                                            REFERENCES event_sessions(session_id)
                                            ON DELETE CASCADE,

                                    CONSTRAINT uk_event_game_states_participant
                                        UNIQUE (participant_id),

                                    CONSTRAINT chk_event_game_states_initial_cash
                                        CHECK (initial_cash > 0),

                                    CONSTRAINT chk_event_game_states_cash_balance
                                        CHECK (cash_balance >= 0),

                                    CONSTRAINT chk_event_game_states_stock_principal
                                        CHECK (stock_principal >= 0),

                                    CONSTRAINT chk_event_game_states_stock_quantity
                                        CHECK (stock_quantity >= 0),

                                    CONSTRAINT chk_event_game_states_deposit_amount
                                        CHECK (deposit_amount >= 0)
);


-- 행사 참가자의 게임 행동 로그를 저장
-- 기존 action_logs(user_id 기준)와 별도이며, 향후 성향 분석 재사용을 위해 유사한 컬럼 구조를 사용
CREATE TABLE event_action_logs (
                                    action_log_id BIGINT NOT NULL AUTO_INCREMENT,
                                    participant_id BIGINT NOT NULL,
                                    session_id BIGINT NOT NULL,
                                    scenario_id VARCHAR(20) NOT NULL,
                                    game_tick INT NOT NULL,
                                    action_type VARCHAR(20) NOT NULL,
                                    asset_type VARCHAR(20) NOT NULL,
                                    action_amount BIGINT NOT NULL,
                                    market_state VARCHAR(20) NOT NULL,
                                    deposit_status VARCHAR(20) NOT NULL,
                                    current_cash BIGINT NOT NULL,
                                    current_stock BIGINT NOT NULL,
                                    current_deposit BIGINT NOT NULL,
                                    rt_score_delta DECIMAL(5,2) NOT NULL,
                                    lh_score_delta DECIMAL(5,2) NOT NULL,
                                    rp_score_delta DECIMAL(5,2) NOT NULL,
                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    PRIMARY KEY (action_log_id),

                                    CONSTRAINT fk_event_action_logs_participant
                                        FOREIGN KEY (participant_id)
                                            REFERENCES event_participants(participant_id)
                                            ON DELETE CASCADE,

                                    CONSTRAINT fk_event_action_logs_session
                                        FOREIGN KEY (session_id)
                                            REFERENCES event_sessions(session_id)
                                            ON DELETE CASCADE,

                                    -- SC001 전체 tick 개수는 시나리오 JSON에서 동적으로 구하므로 상한을 하드코딩하지 않는다
                                    CONSTRAINT chk_event_action_logs_game_tick
                                        CHECK (game_tick >= 0),

                                    CONSTRAINT chk_event_action_logs_action_type
                                        CHECK (
                                            action_type IN (
                                                            'INITIAL_ALLOCATION',
                                                            'BUY',
                                                            'SELL',
                                                            'DEPOSIT_CANCEL'
                                                )
                                            ),

                                    CONSTRAINT chk_event_action_logs_asset_type
                                        CHECK (
                                            asset_type IN (
                                                           'ALL',
                                                           'STOCK',
                                                           'DEPOSIT',
                                                           'CASH'
                                                )
                                            ),

                                    CONSTRAINT chk_event_action_logs_action_amount
                                        CHECK (action_amount >= 0),

                                    CONSTRAINT chk_event_action_logs_market_state
                                        CHECK (
                                            market_state IN (
                                                             'BULL',
                                                             'NORMAL',
                                                             'CRASH',
                                                             'VOLATILE'
                                                )
                                            ),

                                    CONSTRAINT chk_event_action_logs_deposit_status
                                        CHECK (
                                            deposit_status IN (
                                                               'NONE',
                                                               'ACTIVE',
                                                               'MATURED',
                                                               'CANCELLED'
                                                )
                                            ),

                                    CONSTRAINT chk_event_action_logs_current_cash CHECK (current_cash >= 0),
                                    CONSTRAINT chk_event_action_logs_current_stock CHECK (current_stock >= 0),
                                    CONSTRAINT chk_event_action_logs_current_deposit CHECK (current_deposit >= 0),

                                    CONSTRAINT chk_event_action_logs_rt_score_delta CHECK (rt_score_delta BETWEEN -100 AND 100),
                                    CONSTRAINT chk_event_action_logs_lh_score_delta CHECK (lh_score_delta BETWEEN -100 AND 100),
                                    CONSTRAINT chk_event_action_logs_rp_score_delta CHECK (rp_score_delta BETWEEN -100 AND 100)
);

CREATE INDEX idx_event_action_logs_participant_tick_id
    ON event_action_logs (participant_id, game_tick, action_log_id);
