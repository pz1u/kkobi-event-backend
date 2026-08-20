package org.kkobi.assessment.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.domain.ScoreDelta;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentScoreCalculatorTest {

    private final GameScoreCalculator gameScoreCalculator = new GameScoreCalculator();

    @Test
    @DisplayName("게임 점수는 50점에 변화량을 더하고 0부터 100 사이로 제한한다.")
    void calculateGameScore() {
        AssessmentScore assessmentScore = gameScoreCalculator.calculateGameScore(
                List.of(ScoreDelta.createScoreDelta(60, -70, 5))
        );

        assertScoreEquals("100.00", assessmentScore.getRtScore());
        assertScoreEquals("0.00", assessmentScore.getLhScore());
        assertScoreEquals("55.00", assessmentScore.getRpScore());
    }

    private void assertScoreEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
