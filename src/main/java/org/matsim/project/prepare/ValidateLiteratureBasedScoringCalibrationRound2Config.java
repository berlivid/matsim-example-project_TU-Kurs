package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Fail-closed, read-only validation of parameterized literature-based rounds. */
public final class ValidateLiteratureBasedScoringCalibrationRound2Config {
    public static final Path CONFIG = Path.of("scenarios/munich_calibration_2019/"
            + "config_literature_based_scoring_calibration_round_2.xml");
    public static final Path OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "literature-based-scoring-calibration-round-2");
    public static final String RUN_ID =
            "munich-calibration-2019-literature-based-scoring-calibration-round-2";
    public static final Path ROUND_1_ANALYSIS =
            ValidateLiteratureBasedScoringCalibrationRound1Config.OUTPUT.resolve("analysis");
    public static final Path ROUND_1_LATE = ROUND_1_ANALYSIS.resolve(
            "round_1_late_iteration_statistics.csv");
    public static final Path ROUND_1_RECOMMENDATION = ROUND_1_ANALYSIS.resolve(
            "round_1_recommended_next_constants.csv");
    public static final Map<String, Double> EXPECTED_LATE_MEANS = Map.of(
            "car", 32.082277417,
            "pt", 18.475869068,
            "bike", 31.253899367,
            "walk", 18.187954148);
    public static final Map<String, Double> EXPECTED_ASCS = Map.of(
            "car", 0.258598439,
            "pt", 0.611403971,
            "bike", -0.348664107,
            "walk", 0.0);
    public static final int LAST_ITERATION = 60;
    public static final int INNOVATION_LAST_ACTIVE_ITERATION = 48;
    public static final int LATE_FIRST = 51;
    public static final int LATE_LAST = 60;
    public static final Path ROUND_3_CONFIG = Path.of("scenarios/munich_calibration_2019/"
            + "config_literature_based_scoring_calibration_round_3.xml");
    public static final Path ROUND_3_OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "literature-based-scoring-calibration-round-3");
    public static final String ROUND_3_RUN_ID =
            "munich-calibration-2019-literature-based-scoring-calibration-round-3";
    public static final Path ROUND_2_ANALYSIS = OUTPUT.resolve("analysis");
    public static final Path ROUND_2_LATE = ROUND_2_ANALYSIS.resolve(
            "round_2_late_iteration_statistics.csv");
    public static final Path ROUND_2_RECOMMENDATION = ROUND_2_ANALYSIS.resolve(
            "round_2_recommended_next_constants.csv");
    public static final Map<String, Double> ROUND_3_EXPECTED_LATE_MEANS = Map.of(
            "car", 36.717620468,
            "pt", 20.864927803,
            "bike", 28.515656619,
            "walk", 13.901795110);
    public static final Map<String, Double> ROUND_3_EXPECTED_ASCS = Map.of(
            "car", -0.052867606,
            "pt", 0.408378132,
            "bike", -0.851722801,
            "walk", 0.0);
    private static final double EPSILON = 1e-9;

    static final CalibrationSpecification ROUND_2 = new CalibrationSpecification(
            2, CONFIG, OUTPUT, RUN_ID, ROUND_1_LATE, ROUND_1_RECOMMENDATION,
            "31-40", EXPECTED_LATE_MEANS,
            ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_ASCS,
            EXPECTED_ASCS, true, false);
    static final CalibrationSpecification ROUND_3 = new CalibrationSpecification(
            3, ROUND_3_CONFIG, ROUND_3_OUTPUT, ROUND_3_RUN_ID,
            ROUND_2_LATE, ROUND_2_RECOMMENDATION, "51-60",
            ROUND_3_EXPECTED_LATE_MEANS, EXPECTED_ASCS,
            ROUND_3_EXPECTED_ASCS, false, true);

    private ValidateLiteratureBasedScoringCalibrationRound2Config() { }

    public static void main(String[] args) throws Exception {
        CalibrationSpecification specification = specification(args);
        loadAndValidate(specification, true);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING CALIBRATION ROUND-%d VALIDATION PASS%n"
                        + "config=%s%noutput=%s%niterations=0..60 innovation_active_through=48%n"
                        + "late_window=51..60 ASCs=%s walk_reference=0 BOTH_INSIDE=160603%n"
                        + "Original protected population is used. No Controller or QSim was started.%n",
                specification.roundNumber(), specification.config(),
                specification.output(), specification.expectedAscs());
    }

    public static Config loadAndValidate() throws Exception {
        return loadAndValidate(true);
    }

    static Config loadAndValidate(boolean requireOutputAbsent) throws Exception {
        return loadAndValidate(ROUND_2, requireOutputAbsent);
    }

    static Config loadAndValidate(CalibrationSpecification specification,
            boolean requireOutputAbsent) throws Exception {
        require(Files.isRegularFile(specification.config()), "Missing Round-"
                + specification.roundNumber() + " config: " + specification.config());
        if (specification.finalRound()) requireCompleteRound2Analysis();
        Map<String, Double> lateMeans = readLateMeans(
                specification.lateStatistics(), specification.lateWindow());
        require(lateMeans.equals(specification.expectedLateMeans()), "Round-"
                + (specification.roundNumber() - 1)
                + " late means differ from the approved basis: " + lateMeans);
        Map<String, Double> recorded = readRecommendedAscs(
                specification.recommendation());
        require(recorded.equals(specification.expectedAscs()), "Round-"
                + (specification.roundNumber() - 1)
                + " recommendation differs from the approved ASCs: " + recorded);

        Map<String, Double> calculated =
                ValidateLiteratureBasedScoringCalibrationRound1Config.recommendNextAscs(
                        specification.baseAscs(),
                        lateMeans, 0.5);
        for (String mode : specification.expectedAscs().keySet()) {
            require(close(calculated.get(mode), specification.expectedAscs().get(mode)),
                    "Recalculated " + mode + " ASC differs: calculated="
                            + calculated.get(mode) + ", expected="
                            + specification.expectedAscs().get(mode));
        }

        Config base = specification.roundNumber() == 2
                ? ValidateLiteratureBasedScoringCalibrationRound1Config.loadAndValidate(false)
                : loadAndValidate(ROUND_2, false);
        Config candidate = ConfigUtils.loadConfig(specification.config().toString());
        validateRunControl(candidate, specification, requireOutputAbsent);
        validateAscs(candidate, specification.expectedAscs());
        validateOnlyApprovedDifferences(base, candidate,
                specification.allowLastIterationDifference());
        require(candidate.plans().getInputFile().equals(base.plans().getInputFile()),
                "Round " + specification.roundNumber()
                        + " must use the unchanged original input population");
        String plans = candidate.plans().getInputFile().toLowerCase(Locale.ROOT);
        require(!plans.contains("round-1") && !plans.contains("round-2"),
                "Calibration output plans must not be used as input");
        ValidateLiteratureBasedScoringDiagnosticConfig.validateProtectedWorkspace();
        return candidate;
    }

    static void requireCompleteRound2Analysis() {
        for (String name : List.of(
                "round_2_iteration_mode_shares.csv",
                "round_2_late_iteration_statistics.csv",
                "round_2_final_mode_summary.csv",
                "round_2_active_mode_distance_summary.csv",
                "round_2_stuck_events.csv",
                "round_2_recommended_next_constants.csv",
                "round_2_report.md")) {
            require(Files.isRegularFile(ROUND_2_ANALYSIS.resolve(name)),
                    "Incomplete Round-2 analysis; missing "
                            + ROUND_2_ANALYSIS.resolve(name));
        }
    }

    static void validateRunControl(Config config, boolean requireOutputAbsent) {
        validateRunControl(config, ROUND_2, requireOutputAbsent);
    }

    static void validateRunControl(Config config, CalibrationSpecification specification,
            boolean requireOutputAbsent) {
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == LAST_ITERATION,
                "Round " + specification.roundNumber() + " must run iterations 0..60");
        require(specification.runId().equals(config.controller().getRunId()),
                "Unexpected Round-" + specification.roundNumber() + " run ID");
        require(specification.output().normalize().equals(
                        Path.of(config.controller().getOutputDirectory()).normalize()),
                "Unexpected Round-" + specification.roundNumber() + " output directory");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Calibration output must use failIfDirectoryExists");
        require(config.global().getRandomSeed() == 4711, "Random seed must remain 4711");
        require(config.qsim().getEndTime().isDefined()
                        && close(config.qsim().getEndTime().seconds(), 48 * 3600.0),
                "QSim end time must remain 48:00:00");
        require(close(config.qsim().getFlowCapFactor(), 0.05)
                        && close(config.qsim().getStorageCapFactor(), 0.05),
                "Capacity factors must remain 0.05/0.05");
        require(close(config.replanning().getFractionOfIterationsToDisableInnovation(), 0.8),
                "Innovation-disable fraction must remain 0.8");
        require((int) Math.floor(LAST_ITERATION
                        * config.replanning().getFractionOfIterationsToDisableInnovation())
                        == INNOVATION_LAST_ACTIVE_ITERATION,
                "Innovation must be active through iteration 48");
        if (requireOutputAbsent) requireOutputAbsent(specification.output());
    }

    static void validateAscs(Config config) {
        validateAscs(config, EXPECTED_ASCS);
    }

    static void validateAscs(Config config, Map<String, Double> expectedAscs) {
        for (var expected : expectedAscs.entrySet()) {
            var params = config.scoring().getModes().get(expected.getKey());
            require(params != null && close(params.getConstant(), expected.getValue()),
                    "Unexpected " + expected.getKey() + " ASC");
        }
        require(config.scoring().getModes().get("walk").getConstant() == 0.0,
                "Walk must remain the exact zero reference");
    }

    static void validateOnlyApprovedDifferences(Config round1, Config round2) {
        validateOnlyApprovedDifferences(round1, round2, true);
    }

    static void validateOnlyApprovedDifferences(Config base, Config candidate,
            boolean allowLastIterationDifference) {
        String runId = candidate.controller().getRunId();
        String output = candidate.controller().getOutputDirectory();
        int last = candidate.controller().getLastIteration();
        Map<String, Double> ascs = new LinkedHashMap<>();
        for (String mode : List.of("car", "pt", "bike", "walk")) {
            ascs.put(mode, candidate.scoring().getModes().get(mode).getConstant());
        }
        candidate.controller().setRunId(base.controller().getRunId());
        candidate.controller().setOutputDirectory(base.controller().getOutputDirectory());
        if (allowLastIterationDifference) {
            candidate.controller().setLastIteration(base.controller().getLastIteration());
        }
        for (String mode : ascs.keySet()) candidate.scoring().getModes().get(mode)
                .setConstant(base.scoring().getModes().get(mode).getConstant());
        List<String> differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(base, candidate);
        candidate.controller().setRunId(runId);
        candidate.controller().setOutputDirectory(output);
        candidate.controller().setLastIteration(last);
        ascs.forEach((mode, value) -> candidate.scoring().getModes().get(mode).setConstant(value));
        require(differences.isEmpty(), "Calibration round contains unapproved semantic differences:\n- "
                + String.join("\n- ", differences));
    }

    static Map<String, Double> readLateMeans(Path file) throws Exception {
        return readLateMeans(file, "31-40");
    }

    static Map<String, Double> readLateMeans(Path file, String expectedWindow)
            throws Exception {
        require(Files.isRegularFile(file), "Missing late statistics: " + file);
        Map<String, Double> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            require(reader.readLine() != null, "Empty late statistics");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                require(fields.length >= 3 && expectedWindow.equals(fields[1]),
                        "Calibration basis must use late window " + expectedWindow);
                result.put(fields[0], Double.parseDouble(fields[2]));
            }
        }
        return Map.copyOf(result);
    }

    static Map<String, Double> readRecommendedAscs(Path file) throws Exception {
        require(Files.isRegularFile(file), "Missing ASC recommendation: " + file);
        Map<String, Double> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            require(reader.readLine() != null, "Empty ASC recommendation");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                require(fields.length >= 8 && close(Double.parseDouble(fields[5]), 0.5),
                        "ASC recommendation damping must be 0.5");
                result.put(fields[0], Double.parseDouble(fields[7]));
            }
        }
        return Map.copyOf(result);
    }

    static AnalyzeLiteratureBasedScoringCalibrationRound1.RoundDefinition definition() {
        return definition(ROUND_2);
    }

    static AnalyzeLiteratureBasedScoringCalibrationRound1.RoundDefinition definition(
            CalibrationSpecification specification) {
        return new AnalyzeLiteratureBasedScoringCalibrationRound1.RoundDefinition(
                specification.roundNumber(), specification.output(), specification.runId(),
                LAST_ITERATION, LATE_FIRST, LATE_LAST,
                "round_" + specification.roundNumber(), specification.expectedAscs(),
                "ONE_FINAL_ASC_UPDATE_REQUIRED", specification.finalRound());
    }

    static CalibrationSpecification specification(String[] args) {
        if (args.length == 0) return ROUND_2;
        require(args.length == 1 && "--round=3".equals(args[0]),
                "Use no argument for Round 2 or exactly --round=3 for final Round 3");
        return ROUND_3;
    }

    static void requireOutputAbsent(Path output) {
        ValidateLiteratureBasedScoringDiagnosticConfig.requireOutputAbsent(output);
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record CalibrationSpecification(int roundNumber, Path config, Path output,
            String runId, Path lateStatistics, Path recommendation,
            String lateWindow, Map<String, Double> expectedLateMeans,
            Map<String, Double> baseAscs, Map<String, Double> expectedAscs,
            boolean allowLastIterationDifference, boolean finalRound) { }
}
