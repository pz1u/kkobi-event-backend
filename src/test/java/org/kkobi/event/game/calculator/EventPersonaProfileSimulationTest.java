package org.kkobi.event.game.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kkobi.assessment.calculator.PersonaClassifier;
import org.kkobi.assessment.calculator.RecentExtremaMarketStateCalculator;
import org.kkobi.assessment.domain.AssessmentScore;
import org.kkobi.assessment.enums.PersonaType;
import org.kkobi.game.calculator.GameSecurityReturnCalculator;
import org.kkobi.game.dto.ActionLogDto;
import org.kkobi.game.dto.ScenarioDto;
import org.kkobi.game.dto.ScenarioTickDto;
import org.kkobi.game.service.ScenarioService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 2 캘리브레이션 검증:
// - P1~P7 대표 행동 프로필이 목표 성향으로 판정되고 모든 축이 임계값(50)에서 3점 이상 떨어져 있다.
// - LLL은 수학적으로 도달 가능함만 확인한다(현실 빈도 보장 아님 — 유동성 선호 문항 미추가 결정에 따름).
// - 강건성(행동 제거/틱 시프트), 그라인딩 차단, 저활동 커버리지, 현실 모델 독점률,
//   분할매수 하한(5% vs 10%) 민감도를 검증한다.
class EventPersonaProfileSimulationTest {

    private static final long INITIAL_CASH = 10_000_000L;
    private static final int FINAL_TICK = 51;
    private static final int SIMULATION_PARTICIPANTS = 10000;
    private static final double MAX_MONOPOLY_SHARE = 0.50;

    private final RecentExtremaMarketStateCalculator marketStates =
            new RecentExtremaMarketStateCalculator();
    private final EventPersonaFeatureExtractor extractor = new EventPersonaFeatureExtractor(
            marketStates,
            new GameSecurityReturnCalculator()
    );
    private final EventPersonaScoreCalculator scoreCalculator = new EventPersonaScoreCalculator();
    private final PersonaClassifier classifier = new PersonaClassifier();
    private final ScenarioDto scenario = new ScenarioService().getScenario("SC001");

    // 정수 주 단위 거래 지시 (프론트 eventGameTrade.js와 동일한 N×price 꼴)
    private record Trade(int tick, boolean buy, int shares) {
    }

    private record AssessmentResult(EventSessionFeatures f, AssessmentScore score, PersonaType persona) {
    }

    private record ProfileCase(String label, PersonaType target, List<Trade> trades) {
    }

    // ── 실행 헬퍼 ──

    private long priceAt(int tick) {
        return scenario.getTicks().stream()
                .filter(t -> t.getTick() == tick)
                .map(ScenarioTickDto::getPrice)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tick 없음: " + tick));
    }

    // 서버 applyAction과 동일한 산식으로 잔액을 재현하며 로그를 만든다(정수 주 거래).
    private List<ActionLogDto> buildLogs(List<Trade> trades) {
        List<Trade> sorted = trades.stream()
                .sorted(Comparator.comparingInt(Trade::tick))
                .toList();
        List<ActionLogDto> logs = new ArrayList<>();
        ActionLogDto initial = new ActionLogDto();
        initial.setActionLogId(0L);
        initial.setGameTick(0);
        initial.setActionType("INITIAL_ALLOCATION");
        initial.setAssetType("ALL");
        initial.setActionAmount(INITIAL_CASH);
        initial.setCurrentCash(INITIAL_CASH);
        initial.setCurrentStock(0L);
        initial.setCurrentDeposit(0L);
        logs.add(initial);

        long id = 1L;
        long cash = INITIAL_CASH;
        long principal = 0L;
        BigDecimal quantity = BigDecimal.ZERO;

        for (Trade trade : sorted) {
            long price = priceAt(trade.tick());
            long amount = (long) trade.shares() * price;
            BigDecimal tradedQuantity = StockQuantityPolicy.calculateQuantity(amount, price);

            ActionLogDto dto = new ActionLogDto();
            dto.setActionLogId(id++);
            dto.setGameTick(trade.tick());
            dto.setActionType(trade.buy() ? "BUY" : "SELL");
            dto.setAssetType("STOCK");
            dto.setActionAmount(amount);

            if (trade.buy()) {
                cash -= amount;
                principal += amount;
                quantity = quantity.add(tradedQuantity);
            } else {
                long soldPrincipal = StockQuantityPolicy.calculateProportionalPrincipal(
                        principal, tradedQuantity, quantity);
                principal -= soldPrincipal;
                quantity = quantity.subtract(tradedQuantity);
                cash += amount;
            }
            dto.setCurrentCash(cash);
            dto.setCurrentStock(principal);
            dto.setCurrentDeposit(0L);
            logs.add(dto);
        }
        return logs;
    }

    private AssessmentResult runWith(EventPersonaFeatureExtractor targetExtractor, List<Trade> trades) {
        EventExtractionResult extraction = targetExtractor.extract(scenario, buildLogs(trades), FINAL_TICK);
        AssessmentScore score = scoreCalculator.calculate(extraction.features());
        PersonaType persona = classifier.calculatePersona(score);
        return new AssessmentResult(extraction.features(), score, persona);
    }

    private AssessmentResult run(List<Trade> trades) {
        return runWith(extractor, trades);
    }

    private void print(String label, AssessmentResult r) {
        System.out.printf(
                "%s -> %s | RT=%.2f(%+.1f) LH=%.2f(%+.1f) RP=%.2f(%+.1f)%n"
                        + "   avgStock=%s avgCash=%s crashHold=%d panic=%d buyTicks=%d sellTicks=%d "
                        + "rebuy=%d trips=%d absence=%d streak=%s lossAvg=%d dip=%d chase=%d gainAdd=%d split=%d quickTake=%d bullTake=%d%n",
                label, r.persona(),
                r.score().getRtScore(), r.score().getRtScore().doubleValue() - 50,
                r.score().getLhScore(), r.score().getLhScore().doubleValue() - 50,
                r.score().getRpScore(), r.score().getRpScore().doubleValue() - 50,
                r.f().avgStockRatio(), r.f().avgCashRatio(),
                r.f().crashHoldingEpisodes(), r.f().panicFullSellCount(),
                r.f().distinctBuyTicks(), r.f().distinctSellTicks(),
                r.f().rebuyWithin2TicksCount(), r.f().completedRoundTrips(),
                r.f().reentryAbsenceCount(), r.f().accumulationStreak(),
                r.f().lossAveragingBuyCount(), r.f().crashDipBuyCount(),
                r.f().bullChaseBuyCount(), r.f().gainAddBuyCount(), r.f().normalSplitBuyCount(),
                r.f().quickProfitTakeCount(), r.f().bullProfitTakeCount());
    }

    // ── P1~P7 대표 행동 스크립트 ──
    // SC001 이벤트 국면: 정상 t0-19 / 급락 t20-27 / 정상 28-29 / 급등 30-34 / 정상 35-40 / 급등 41-47

    private List<Trade> p1Script() {
        // 불꽃 추격자: 대량 진입 + 수익 중 추가 + 급락 홀딩·역발 + 양쪽 급등장 추격 + 분할 청산 왕복
        return List.of(
                new Trade(5, true, 250),
                new Trade(15, true, 50),
                new Trade(19, true, 40),
                new Trade(25, true, 80),    // 급락 역발 1
                new Trade(26, true, 40),    // 급락 역발 2
                new Trade(28, false, 230),  // 분할 청산 1
                new Trade(29, false, 230),  // 청산 완료 -> 왕복 1
                new Trade(31, true, 180),   // 급등 추격 1
                new Trade(33, true, 100),   // 급등 추격 2
                new Trade(34, true, 80),    // 급등 추격 3
                new Trade(36, true, 60),    // 수익 중 추가
                new Trade(48, false, 150),  // 분할 청산 (마지막 매수 12틱 뒤 -> 신속 익절 아님)
                new Trade(49, false, 150),
                new Trade(50, false, 120)   // 청산 완료 -> 왕복 2
        );
    }

    private List<Trade> p2Script() {
        // 스마트 단타러: 대형 왕복 사이클 반복 + 급락장은 풀보유로 버티고 종료 직후 정리 + 급등 추격 후 신속 익절
        return List.of(
                new Trade(2, true, 400),
                new Trade(5, false, 400),   // 왕복 1 (신속 익절)
                new Trade(11, true, 450),   // 급락 풀보유 진입
                new Trade(29, false, 450),  // 급락 종료 직후 청산 -> 왕복 2
                new Trade(31, true, 450),   // 반등 추격 1
                new Trade(35, false, 440),  // 신속 익절
                new Trade(41, true, 420),   // 급등 추격 2
                new Trade(46, false, 430)   // 청산 완료 -> 왕복 3 (신속 익절)
        );
    }

    private List<Trade> p3Script() {
        // 야망찬 개척자: 조기 대량 진입 + 끝까지 홀딩 + 급락 저점/양쪽 급등 추격 추가 매수, 매도 없음
        return List.of(
                new Trade(2, true, 420),
                new Trade(15, true, 30),
                new Trade(25, true, 80),    // 급락 역발
                new Trade(32, true, 30),    // 급등 추격 1
                new Trade(34, true, 20),    // 급등 추격 2
                new Trade(43, true, 20)     // 급등 추격 3
        );
    }

    private List<Trade> p4Script() {
        // 신념의 가치투자자: 초기 풀매수 후 무매매 홀딩
        return List.of(
                new Trade(2, true, 498)
        );
    }

    private List<Trade> p5Script() {
        // 실속파 정보통: 소액 분할 진입 + 급락 역발 반복 + 여유 있는 단계 익절
        return List.of(
                new Trade(10, true, 50),    // 정상장 소액 진입 (분할)
                new Trade(23, true, 100),   // 급락 역발 1
                new Trade(26, true, 80),    // 급락 역발 2
                new Trade(27, true, 40),    // 급락 역발 3
                new Trade(28, false, 60),   // 손실 매도(중립)
                new Trade(33, true, 30),    // 급등 추격
                new Trade(35, true, 20),    // 수익 중 추가
                new Trade(48, false, 100),  // 단계 익절
                new Trade(49, false, 100),
                new Trade(50, false, 90)    // 최종 청산 -> 왕복 완결
        );
    }

    private List<Trade> p6Script() {
        // 현금 확보주의자: 무행동
        return List.of();
    }

    private List<Trade> p7Script() {
        // 묵묵한 적립왕: 정상장 분할 매수 후 유지, 급락장에도 소액 적립, 매도 없음
        return List.of(
                new Trade(2, true, 50),
                new Trade(10, true, 50),
                new Trade(16, true, 25),
                new Trade(24, true, 50),    // 급락 역발
                new Trade(28, true, 20),
                new Trade(36, true, 20),
                new Trade(45, true, 20)     // 급등 중 소액
        );
    }

    private List<ProfileCase> profileCases() {
        return List.of(
                new ProfileCase("P1 불꽃추격자", PersonaType.HHH, p1Script()),
                new ProfileCase("P2 스마트단타러", PersonaType.HHL, p2Script()),
                new ProfileCase("P3 야망개척자", PersonaType.HLH, p3Script()),
                new ProfileCase("P4 신념가치투자자", PersonaType.HLL, p4Script()),
                new ProfileCase("P5 실속파정보통", PersonaType.LHH, p5Script()),
                new ProfileCase("P6 무행동", PersonaType.LHL, p6Script()),
                new ProfileCase("P7 적립왕", PersonaType.LLH, p7Script())
        );
    }

    @Test
    @DisplayName("진단: P1~P7 프로필 점수/성향 출력 (캘리브레이션용)")
    void printProfileDiagnostics() {
        for (ProfileCase c : profileCases()) {
            print(c.label(), run(c.trades()));
        }
    }

    @Test
    @DisplayName("P1~P7 대표 행동 프로필이 목표 성향으로 판정되고 모든 축이 임계값에서 3점 이상 떨어져 있다")
    void profilesClassifyAsTargetPersonasWithMargins() {
        for (ProfileCase c : profileCases()) {
            assertProfile(c.label(), run(c.trades()), c.target());
        }
    }

    private void assertProfile(String label, AssessmentResult result, PersonaType expected) {
        assertEquals(expected, result.persona(), label + " 성향 불일치");
        assertAxisMargin(label + " RT", result.score().getRtScore());
        assertAxisMargin(label + " LH", result.score().getLhScore());
        assertAxisMargin(label + " RP", result.score().getRpScore());
    }

    private void assertAxisMargin(String label, BigDecimal score) {
        double distanceFromThreshold = Math.abs(score.doubleValue() - 50.0);
        assertTrue(distanceFromThreshold >= 3.0,
                label + " 축이 판정 임계값(50)에 너무 가깝다: " + score);
    }

    // ── ⑧ LLL 도달 가능성 (합성 특징 기반) ──

    @Test
    @DisplayName("⑧ LLL: 합성 특징으로 수학적 도달 가능성만 확인한다(현실 빈도 보장 아님)")
    void lllIsMathematicallyReachable() {
        // 저노출(48%) + 미회수 투입 소량(netPhase -4) + 신속/급등 익절 부정 + 재진입 없음 조합
        EventSessionFeatures features = features(
                bd("48"), bd("52"),
                /*buyTickSpan*/12,
                /*crashHold*/0, /*panicSell*/0,
                /*buyTicks*/3, /*sellTicks*/1, /*rebuy*/0, /*trips*/0, /*absence*/1, false,
                0, 0, 0, 0, 0,
                /*quickTake*/2, /*bullTake*/1);

        AssessmentScore score = scoreCalculator.calculate(features);
        assertEquals(PersonaType.LLL, classifier.calculatePersona(score));
    }

    // ── ⑨ 강건성 검증 ──

    @Test
    @DisplayName("⑨ 강건성: 행동 하나를 제거해도 과반수 변형에서 같은 성향이 유지된다")
    void removingSingleActionKeepsPersonaInMostVariants() {
        long combinedStable = 0L;
        long combinedTotal = 0L;
        for (ProfileCase c : profileCases()) {
            if (c.trades().size() < 2) continue;   // 단일·무행동 프로필은 제거 변형 자체가 없다
            long stable = 0L;
            for (int index = 0; index < c.trades().size(); index++) {
                List<Trade> variant = new ArrayList<>(c.trades());
                variant.remove(index);
                if (run(variant).persona() == c.target()) stable++;
            }
            System.out.printf("%s 제거 강건성: %.0f%% (%d/%d)%n",
                    c.label(), 100.0 * stable / c.trades().size(), stable, c.trades().size());
            combinedStable += stable;
            combinedTotal += c.trades().size();
        }
        assertTrue(combinedStable / (double) combinedTotal >= 0.5,
                "전체 제거 강건성 부족: " + combinedStable + "/" + combinedTotal);
    }

    @Test
    @DisplayName("⑨ 강건성: 시장 상태가 같은 틱 범위에서 타이밍이 밀려도 성향이 유지된다")
    void tickShiftWithinSameMarketStateKeepsPersona() {
        for (ProfileCase c : profileCases()) {
            if (c.trades().isEmpty()) continue;
            List<Trade> shifted = new ArrayList<>();
            for (Trade trade : c.trades()) {
                int nextTick = trade.tick() + 1;
                boolean stateChangesAcrossBoundary =
                        nextTick > FINAL_TICK
                                || marketStates.calculateMarketState(scenario, trade.tick())
                                != marketStates.calculateMarketState(scenario, nextTick);
                if (stateChangesAcrossBoundary) {
                    shifted.add(trade);
                    continue;
                }
                shifted.add(new Trade(nextTick, trade.buy(), trade.shares()));
            }
            assertEquals(c.target(), run(shifted).persona(), c.label() + " 틱 시프트 성향 변경");
        }
    }

    // 반복 소액 분할매수가 중반에 LLH로 수렴하는 것은 "적립 행동의 극한 = 적립왕"으로
    // 의미론적으로 일치하는 결과이며, 요구사항이 금지하는 것은 금고지기(LLL)류 무관 성향 관통이다.
    @Test
    @DisplayName("⑨ 강건성: 동일 매수만 반복해도 금고지기(LLL)로 갈 수 없고 궤적이 bounded다")
    void grindingRepeatedBuysStayConsistent() {
        List<Integer> normalBuyTicks = List.of(2, 4, 6, 8, 10, 12, 14, 16, 18);
        Set<PersonaType> seen = new HashSet<>();
        List<String> trajectory = new ArrayList<>();
        for (int count = 1; count <= normalBuyTicks.size(); count++) {
            List<Trade> trades = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                trades.add(new Trade(normalBuyTicks.get(index), true, 30));
            }
            AssessmentResult result = run(trades);
            seen.add(result.persona());
            trajectory.add(count + "회:" + result.persona());
        }
        System.out.println("그라인딩 궤적: " + String.join(", ", trajectory));
        assertFalse(seen.contains(PersonaType.LLL),
                "반복 매수만으로 철저한 금고지기(LLL)가 나올 수 없다");
        assertFalse(seen.contains(PersonaType.HHH),
                "소액 분할매수 반복으로 불꽃 추격자(HHH)가 나올 수 없다");
        assertTrue(seen.size() <= 4,
                "반복 매수 궤적의 성향 변동이 과도하다: " + seen);
    }

    @Test
    @DisplayName("⑨ 커버리지: 저활동(0~3회 거래) 스크립트에서도 복수 성향이 도달한다")
    void lowActivityScriptsReachMultiplePersonas() {
        List<List<Trade>> lowActivityScripts = List.of(
                List.of(),                                                      // 무행동 -> LHL
                List.of(new Trade(2, true, 498)),                               // 초기 풀매수 홀딩 -> HLL
                List.of(new Trade(2, true, 498),                                // 조기 진입 후 단계 청산
                        new Trade(30, false, 200), new Trade(49, false, 298)),  // -> HHL 계열
                List.of(new Trade(2, true, 150),                                // 세션 전반 소액 분할
                        new Trade(10, true, 120), new Trade(18, true, 90)),     // -> LLH/LHH 계열
                List.of(new Trade(25, true, 250)),                              // 급락 역발 한 번
                List.of(new Trade(2, true, 100),                                // 세션 전반 원칙 진입
                        new Trade(16, true, 80), new Trade(24, true, 120),
                        new Trade(36, true, 60))                                // -> LLH 계열
        );
        Set<PersonaType> seen = new HashSet<>();
        List<String> labels = List.of("무행동", "풀매수홀딩", "단계청산", "세션분할", "급락역발", "미니회전");
        for (int index = 0; index < lowActivityScripts.size(); index++) {
            AssessmentResult result = run(lowActivityScripts.get(index));
            seen.add(result.persona());
            print("저활동[" + labels.get(index) + "]", result);
        }
        System.out.println("저활동 커버리지: " + seen);
        assertTrue(seen.size() >= 4,
                "저활동 구간에서 도달한 성향 수 부족: " + seen);
    }

    // ── 현실 모델 독점률 + 분할매수 하한 민감도 ──

    // 행동 빈도와 매수 성향이 서로 다른 참가자를 무작위 생성한다(정수 주 단위).
    private List<Trade> generateRandomTrades(long seed) {
        Random random = new Random(seed);
        double activity = random.nextDouble();
        double riskAppetite = random.nextDouble();
        List<Trade> trades = new ArrayList<>();
        long cash = INITIAL_CASH;
        long shares = 0L;
        int[] sizeBucketsPercent = {10, 15, 25, 40};

        for (int tick = 2; tick <= 50; tick++) {
            if (random.nextDouble() > 0.03 + activity * 0.20) continue;
            long price = priceAt(tick);
            boolean buy = shares == 0L || random.nextDouble() < 0.35 + riskAppetite * 0.35;
            int bucketPercent = sizeBucketsPercent[random.nextInt(sizeBucketsPercent.length)];
            if (buy) {
                int buyShares = (int) Math.min(cash * bucketPercent / 100 / price, 800);
                if (buyShares <= 0) continue;
                trades.add(new Trade(tick, true, buyShares));
                cash -= (long) buyShares * price;
                shares += buyShares;
            } else {
                int sellShares = (int) Math.min(shares,
                        Math.max(1L, Math.round(shares * bucketPercent / 100.0)));
                trades.add(new Trade(tick, false, sellShares));
                cash += (long) sellShares * price;
                shares -= sellShares;
            }
        }
        return trades;
    }

    private Map<PersonaType, Integer> tallyWith(EventPersonaFeatureExtractor targetExtractor) {
        Map<PersonaType, Integer> counts = new EnumMap<>(PersonaType.class);
        for (long seed = 1; seed <= SIMULATION_PARTICIPANTS; seed++) {
            counts.merge(personaOf(targetExtractor, generateRandomTrades(seed)), 1, Integer::sum);
        }
        return counts;
    }

    private PersonaType personaOf(EventPersonaFeatureExtractor targetExtractor, List<Trade> trades) {
        return classifier.calculatePersona(scoreCalculator.calculate(
                targetExtractor.extract(scenario, buildLogs(trades), FINAL_TICK).features()));
    }

    private double maxShare(Map<PersonaType, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return total == 0 ? 0.0
                : counts.values().stream().mapToInt(Integer::intValue).max().orElse(0) / (double) total;
    }

    private String sortedCounts(Map<PersonaType, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<PersonaType, Integer>comparingByValue().reversed())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("없음");
    }

    @Test
    @DisplayName("현실 모델 시뮬레이션: 어떤 성향도 50%를 넘게 독점하지 않는다")
    void realisticModelHasNoDominantPersona() {
        Map<PersonaType, Integer> counts = tallyWith(extractor);
        System.out.printf("현실 모델 분포(%d명): %s%n", SIMULATION_PARTICIPANTS, sortedCounts(counts));
        assertTrue(maxShare(counts) < MAX_MONOPOLY_SHARE,
                "특정 성향 독점률 초과: " + String.format("%.1f%%", maxShare(counts) * 100));
    }

    @Test
    @DisplayName("⑪ 민감도: 채택값(하한 5%)은 독점률 기준을 통과하고, 10%는 악화되어 5% 유지 근거가 된다")
    void splitBuyThresholdSensitivityFiveVsTenPercent() {
        EventPersonaFeatureExtractor tenPercentExtractor = new EventPersonaFeatureExtractor(
                marketStates,
                new GameSecurityReturnCalculator(),
                EventPersonaThresholds.defaults().withSplitBuyMinRatio(10));

        Map<PersonaType, Integer> fivePercentCounts = tallyWith(extractor);
        Map<PersonaType, Integer> tenPercentCounts = tallyWith(tenPercentExtractor);
        double fiveMaxShare = maxShare(fivePercentCounts);
        double tenMaxShare = maxShare(tenPercentCounts);
        System.out.printf("분할 하한 5%%(채택): 최대 독점 %.1f%%, %s%n",
                fiveMaxShare * 100, sortedCounts(fivePercentCounts));
        System.out.printf("분할 하한 10%%(비교): 최대 독점 %.1f%%, %s%n",
                tenMaxShare * 100, sortedCounts(tenPercentCounts));

        // 채택값(5%)은 요구사항 19번 독점률 기준(<50%)을 통과해야 한다.
        assertTrue(fiveMaxShare < MAX_MONOPOLY_SHARE,
                "하한 5% 독점률 초과: " + String.format("%.1f%%", fiveMaxShare * 100));

        // 하한 10%는 합격 단정이 아니라 비교 관측치다. 실측상 10%로 올리면 소액 분할이
        // 걸러져 LHL/LLL로 이동해 독점률이 악화되므로 5% 유지를 뒷받침하는 데이터로 남긴다.
        // (완전 붕괴 수준 확인용 상한만 둔다)
        assertTrue(tenMaxShare < 0.70,
                "하한 10% 분포가 왜곡 한계를 초과: " + String.format("%.1f%%", tenMaxShare * 100));

        // P7 적립왕은 두 임계값에서 모두 유지된다(소액 분할 일부가 걸러져도 여유 마진 존재).
        assertEquals(PersonaType.LLH, personaOf(extractor, p7Script()),
                "하한 5%에서 P7 유지 실패");
        assertEquals(PersonaType.LLH, personaOf(tenPercentExtractor, p7Script()),
                "하한 10%에서 P7 유지 실패");
    }

    // ── 헬퍼 ──

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // EventSessionFeatures 컴포넌트 순서대로 생성한다.
    private EventSessionFeatures features(BigDecimal avgStockRatio, BigDecimal avgCashRatio,
            int buyTickSpan, int crashHoldingEpisodes, int panicFullSellCount,
            int distinctBuyTicks, int distinctSellTicks, int rebuyWithin2TicksCount,
            int completedRoundTrips, int reentryAbsenceCount, boolean accumulationStreak,
            int lossAveragingBuyCount, int crashDipBuyCount, int bullChaseBuyCount,
            int gainAddBuyCount, int normalSplitBuyCount, int quickProfitTakeCount,
            int bullProfitTakeCount) {
        return new EventSessionFeatures(avgStockRatio, avgCashRatio, buyTickSpan,
                crashHoldingEpisodes, panicFullSellCount, distinctBuyTicks, distinctSellTicks,
                rebuyWithin2TicksCount, completedRoundTrips, reentryAbsenceCount,
                accumulationStreak, lossAveragingBuyCount, crashDipBuyCount, bullChaseBuyCount,
                gainAddBuyCount, normalSplitBuyCount, quickProfitTakeCount, bullProfitTakeCount);
    }
}
