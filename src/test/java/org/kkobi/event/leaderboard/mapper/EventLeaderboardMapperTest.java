package org.kkobi.event.leaderboard.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kkobi.config.RootConfig;
import org.kkobi.event.leaderboard.domain.EventLeaderboardRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * findRankings SQL의 RANK() 공동순위(1,2,2,4)와 동일 순위 내부 deterministic 정렬 검증.
 * 실제 DB에 삽입 후 쿼리 결과를 검증하며 @Transactional로 자동 롤백된다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {RootConfig.class})
@Transactional
class EventLeaderboardMapperTest {

    @Autowired
    private EventLeaderboardMapper eventLeaderboardMapper;

    private JdbcTemplate jdbc;

    @Autowired
    void setDataSource(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    private Long createSession() {
        jdbc.update(
                "INSERT INTO event_sessions (status, scenario_id, duration_seconds, initial_cash) " +
                        "VALUES ('FINISHED', 'SC001', 180, 10000000)"
        );
        return jdbc.queryForObject(
                "SELECT session_id FROM event_sessions ORDER BY session_id DESC LIMIT 1", Long.class);
    }

    private Long createPersona() {
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update(
                "INSERT INTO personas (persona_name, stock_ratio, bond_ratio, deposit_ratio) " +
                        "VALUES (?, 40, 30, 30)",
                "성향-" + uid
        );
        return jdbc.queryForObject(
                "SELECT persona_id FROM personas ORDER BY persona_id DESC LIMIT 1", Long.class);
    }

    private Long createParticipant(Long sessionId, String nickname) {
        String token = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO event_participants (session_id, nickname, participant_token) VALUES (?, ?, ?)",
                sessionId, nickname, token
        );
        return jdbc.queryForObject(
                "SELECT participant_id FROM event_participants WHERE participant_token = ?", Long.class, token);
    }

    private void createResult(Long participantId, Long sessionId, Long personaId, String returnRate) {
        jdbc.update(
                "INSERT INTO event_game_results (" +
                        "participant_id, session_id, persona_id, initial_asset, final_asset, return_rate, " +
                        "rt_score, lh_score, rp_score, final_tick, final_price, finished_at) " +
                        "VALUES (?, ?, ?, 10000000, 11000000, ?, 50.00, 50.00, 50.00, 30, 15000, NOW())",
                participantId, sessionId, personaId, new BigDecimal(returnRate)
        );
    }

    @Test
    @DisplayName("동일 수익률은 같은 순위를 받고 다음 순위는 그만큼 밀린다 (RANK, 1/2/2/4)")
    void assignsCompetitionRanking() {
        Long sessionId = createSession();
        Long personaId = createPersona();

        Long a = createParticipant(sessionId, "A");
        Long b = createParticipant(sessionId, "B");
        Long c = createParticipant(sessionId, "C");
        Long d = createParticipant(sessionId, "D");

        createResult(a, sessionId, personaId, "12.31");
        createResult(b, sessionId, personaId, "10.22");
        createResult(c, sessionId, personaId, "10.22");
        createResult(d, sessionId, personaId, "8.91");

        List<EventLeaderboardRow> rows = eventLeaderboardMapper.findRankings(sessionId);

        assertEquals(4, rows.size());
        assertEquals(1L, rows.get(0).getRank());
        assertEquals("A", rows.get(0).getNickname());
        assertEquals(2L, rows.get(1).getRank());
        assertEquals(2L, rows.get(2).getRank());
        assertEquals(4L, rows.get(3).getRank());
        assertEquals("D", rows.get(3).getNickname());
    }

    @Test
    @DisplayName("동일 순위 내부는 nickname 오름차순으로 deterministic하게 정렬된다")
    void ordersTiedRanksDeterministically() {
        Long sessionId = createSession();
        Long personaId = createPersona();

        Long z = createParticipant(sessionId, "Z-닉네임");
        Long a = createParticipant(sessionId, "A-닉네임");

        createResult(z, sessionId, personaId, "10.00");
        createResult(a, sessionId, personaId, "10.00");

        List<EventLeaderboardRow> rows = eventLeaderboardMapper.findRankings(sessionId);

        assertEquals(2, rows.size());
        assertEquals("A-닉네임", rows.get(0).getNickname());
        assertEquals("Z-닉네임", rows.get(1).getNickname());
        assertEquals(1L, rows.get(0).getRank());
        assertEquals(1L, rows.get(1).getRank());
    }
}
