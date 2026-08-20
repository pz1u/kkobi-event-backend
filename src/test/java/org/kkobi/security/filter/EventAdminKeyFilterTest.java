package org.kkobi.security.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventAdminKeyFilterTest {

    private final EventAdminKeyFilter filter = new EventAdminKeyFilter();

    @Test
    @DisplayName("/api/admin/event로 시작하지 않는 경로는 필터를 거치지 않는다")
    void shouldNotFilterNonAdminEventPaths() {
        filter.adminKey = "correct-key";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn("/api/event/status");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("/api/admin/event 경로는 필터 대상이다")
    void filtersAdminEventPaths() {
        filter.adminKey = "correct-key";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn("/api/admin/event/start");

        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("EVENT_ADMIN_KEY가 서버에 설정되지 않으면 항상 401로 거절한다 (fail-closed)")
    void rejectsWhenAdminKeyNotConfigured() throws Exception {
        filter.adminKey = "";
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Admin-Key 헤더가 없으면 401을 반환한다")
    void rejectsWhenHeaderMissing() throws Exception {
        filter.adminKey = "correct-key";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Admin-Key")).thenReturn(null);
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Admin-Key 값이 틀리면 401을 반환한다")
    void rejectsWhenKeyMismatch() throws Exception {
        filter.adminKey = "correct-key";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Admin-Key")).thenReturn("wrong-key");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Admin-Key 값이 올바르면 다음 필터로 통과시킨다")
    void passesWhenKeyMatches() throws Exception {
        filter.adminKey = "correct-key";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Admin-Key")).thenReturn("correct-key");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
