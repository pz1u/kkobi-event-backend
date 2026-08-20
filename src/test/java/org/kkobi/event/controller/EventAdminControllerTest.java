package org.kkobi.event.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kkobi.event.dto.response.EventAdminParticipantListResponse;
import org.kkobi.event.dto.response.EventAdminParticipantResponse;
import org.kkobi.event.dto.response.EventStatusResponse;
import org.kkobi.event.leaderboard.dto.response.EventAdminLeaderboardResponse;
import org.kkobi.event.leaderboard.dto.response.EventLeaderboardEntry;
import org.kkobi.event.leaderboard.service.EventLeaderboardService;
import org.kkobi.event.service.EventAdminService;
import org.kkobi.event.service.EventParticipantService;
import org.kkobi.event.service.EventSessionService;
import org.kkobi.exception.CommonExceptionAdvice;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// X-Admin-Key 검증은 EventAdminKeyFilter(Spring Security 필터 체인)에서 처리되므로
// standalone MockMvc(필터 체인 미포함)에서는 컨트롤러-서비스 배선과 응답 형태만 검증한다.
@ExtendWith(MockitoExtension.class)
class EventAdminControllerTest {

    private MockMvc mvc;

    @Mock
    private EventSessionService eventSessionService;
    @Mock
    private EventParticipantService eventParticipantService;
    @Mock
    private EventLeaderboardService eventLeaderboardService;
    @Mock
    private EventAdminService eventAdminService;

    @BeforeEach
    void setUp() {
        EventAdminController controller = new EventAdminController(
                eventSessionService, eventParticipantService, eventLeaderboardService, eventAdminService);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build()
        );

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CommonExceptionAdvice())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("행사 시작 요청은 EventSessionService.startEvent()에 위임한다")
    void startDelegatesToSessionService() throws Exception {
        when(eventSessionService.startEvent()).thenReturn(new EventStatusResponse(
                1L, "COUNTDOWN", "SC001", LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(), 180, 10_000_000L, 3
        ));

        mvc.perform(post("/api/admin/event/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTDOWN"));
    }

    @Test
    @DisplayName("행사 강제 종료 요청은 EventSessionService.finishEvent()에 위임한다")
    void finishDelegatesToSessionService() throws Exception {
        when(eventSessionService.finishEvent()).thenReturn(new EventStatusResponse(
                1L, "FINISHED", "SC001", LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(), 180, 10_000_000L, 3
        ));

        mvc.perform(post("/api/admin/event/finish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"));
    }

    @Test
    @DisplayName("관리자 참가자 목록은 participantToken 없이 participantId/nickname만 반환한다")
    void getParticipantsReturnsListWithoutToken() throws Exception {
        when(eventParticipantService.getParticipantsForAdmin()).thenReturn(
                new EventAdminParticipantListResponse(2, List.of(
                        new EventAdminParticipantResponse(1L, "투자왕"),
                        new EventAdminParticipantResponse(2L, "개미왕")
                ))
        );

        mvc.perform(get("/api/admin/event/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.participants[0].nickname").value("투자왕"))
                .andExpect(jsonPath("$.participants[0].participantId").value(1));
    }

    @Test
    @DisplayName("관리자 리더보드는 myRank/myReturnRate 없이 순위만 반환한다")
    void getLeaderboardReturnsRankingsOnly() throws Exception {
        when(eventLeaderboardService.getLeaderboardForAdmin()).thenReturn(
                new EventAdminLeaderboardResponse(1L, 1, List.of(
                        new EventLeaderboardEntry(1, 7L, "투자왕", 11_231_000L,
                                new BigDecimal("12.31"), 2L, "불꽃 추격자")
                ))
        );

        mvc.perform(get("/api/admin/event/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantCount").value(1))
                .andExpect(jsonPath("$.rankings[0].rank").value(1))
                .andExpect(jsonPath("$.rankings[0].returnRate").value(12.31))
                .andExpect(jsonPath("$.myRank").doesNotExist())
                .andExpect(jsonPath("$.myReturnRate").doesNotExist());
    }

    @Test
    @DisplayName("행사 초기화 요청은 EventAdminService.resetEvent()에 위임한다")
    void resetDelegatesToAdminService() throws Exception {
        when(eventAdminService.resetEvent()).thenReturn(new EventStatusResponse(
                1L, "WAITING", "SC001", LocalDateTime.now(), null,
                null, null, 180, 10_000_000L, 0
        ));

        mvc.perform(post("/api/admin/event/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.participantCount").value(0));
    }
}
