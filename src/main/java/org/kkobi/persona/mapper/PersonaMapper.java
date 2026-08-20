package org.kkobi.persona.mapper;

import org.apache.ibatis.annotations.Param;
import org.kkobi.persona.dto.PersonaResponseDto;

import java.util.List;

public interface PersonaMapper {

    List<PersonaResponseDto> findAllPersonas();

    // personaId로 단일 페르소나 상세 조회 (행사 결과 등에서 이름/설명 조회에 재사용)
    PersonaResponseDto findPersonaById(@Param("personaId") Long personaId);
}