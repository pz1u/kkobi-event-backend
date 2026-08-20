package org.kkobi.event.service;

import lombok.RequiredArgsConstructor;
import org.kkobi.event.domain.EventParticipant;
import org.kkobi.event.domain.EventSession;
import org.kkobi.event.dto.request.EventParticipantJoinRequest;
import org.kkobi.event.dto.response.EventParticipantJoinResponse;
import org.kkobi.event.dto.response.EventParticipantMeResponse;
import org.kkobi.event.enums.EventSessionStatus;
import org.kkobi.event.exception.DuplicateNicknameException;
import org.kkobi.event.exception.EventJoinNotAllowedException;
import org.kkobi.event.exception.InvalidParticipantTokenException;
import org.kkobi.event.mapper.EventParticipantMapper;
import org.kkobi.event.mapper.EventSessionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventParticipantServiceImpl implements EventParticipantService {

    private static final int NICKNAME_MAX_LENGTH = 30;

    private final EventSessionMapper eventSessionMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final EventSessionService eventSessionService;

    // 신규 참가자를 닉네임으로 등록하고 participantToken을 발급
    @Override
    @Transactional
    public EventParticipantJoinResponse join(EventParticipantJoinRequest request) {
        String nickname = normalizeNickname(request.getNickname());

        EventSession session = eventSessionMapper.findCurrentSession();
        if (session == null) {
            throw new IllegalStateException("진행 중인 행사가 없습니다.");
        }
        if (session.getStatus() != EventSessionStatus.WAITING) {
            throw new EventJoinNotAllowedException("게임이 이미 시작되어 신규 참여할 수 없습니다.");
        }

        if (eventParticipantMapper.existsBySessionIdAndNickname(session.getSessionId(), nickname)) {
            throw new DuplicateNicknameException("이미 사용 중인 닉네임입니다.");
        }

        EventParticipant participant = new EventParticipant();
        participant.setSessionId(session.getSessionId());
        participant.setNickname(nickname);
        participant.setParticipantToken(UUID.randomUUID().toString());

        // 동시 요청으로 인한 닉네임 중복은 DB UNIQUE 제약조건을 최종 방어선으로 사용
        try {
            eventParticipantMapper.saveParticipant(participant);
        } catch (DuplicateKeyException e) {
            throw new DuplicateNicknameException("이미 사용 중인 닉네임입니다.");
        }

        return new EventParticipantJoinResponse(
                participant.getParticipantId(),
                participant.getNickname(),
                participant.getParticipantToken(),
                session.getSessionId(),
                session.getStatus().name()
        );
    }

    // participantToken으로 기존 참가자 정보를 조회 (eventStatus는 동기화된 최신 상태)
    @Override
    @Transactional
    public EventParticipantMeResponse getMe(String participantToken) {
        if (participantToken == null || participantToken.isBlank()) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }

        EventParticipant participant = eventParticipantMapper.findByParticipantToken(participantToken);
        if (participant == null) {
            throw new InvalidParticipantTokenException("유효하지 않은 참가자 정보입니다.");
        }

        EventSessionStatus status = eventSessionService.getSynchronizedStatus(participant.getSessionId());

        return new EventParticipantMeResponse(
                participant.getParticipantId(),
                participant.getSessionId(),
                participant.getNickname(),
                status.name()
        );
    }

    // 앞뒤 공백을 제거하고 필수 입력 및 길이 제약을 검증
    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw new IllegalArgumentException("닉네임은 필수 입력 값입니다.");
        }
        String trimmed = nickname.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("닉네임은 필수 입력 값입니다.");
        }
        if (trimmed.length() > NICKNAME_MAX_LENGTH) {
            throw new IllegalArgumentException("닉네임은 " + NICKNAME_MAX_LENGTH + "자를 초과할 수 없습니다.");
        }
        return trimmed;
    }
}
