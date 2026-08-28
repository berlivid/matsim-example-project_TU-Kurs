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

/** Fail-closed, read-only validation of literature-based calibration Round 2. */
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
    private static final double EPSILON = 1e-9;

    private ValidateLiteratureBasedScoringCalibrationRound2Config() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The Round-2 validator accepts no arguments");
        loadAndValidate(true);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING CALIBRATION ROUND-2 VALIDATION PASS%n"
                        + "config=%s%noutput=%s%niterations=0..60 innovation_active_through=48%n"
                        + "late_window=51..60 ASCs=%s walk_reference=0 BOTH_INSIDE=160603%n"
                        + "Original protected population is used. No Controller or QSim was started.%n",
                CONFIG, OUTPUT, EXPECTED_ASCS);
    }

    public static Config loadAndValidate() throws Exception {
        return loadAndValidate(true);
    }

    static Config loadAndValidate(boolean requireOutputAbsent) throws Exception {
        require(Files.isRegularFile(CONFIG), "Missing Round-2 config: " + CONFIG);
        Map<String, Double> lateMeans = readLateMeans(ROUND_1_LATE);
        require(lateMeans.equals(EXPECTED_LATE_MEANS),
                "Round-1 late means differ from the approved basis: " + lateMeans);
        Map<String, Double> recorded = readRecommendedAscs(ROUND_1_RECOMMENDATION);
        require(recorded.equals(EXPECTED_ASCS),
                "Round-1 recommendation differs from the approved ASCs: " + recorded);

        Map<String, Double> calculated =
                ValidateLiteratureBasedScoringCalibrationRound1Config.recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_ASCS,
                        lateMeans, 0.5);
        for (String mode : EXPECTED_ASCS.keySet()) {
            require(close(calculated.get(mode), EXPECTED_ASCS.get(mode)),
                    "Recalculated " + mode + " ASC differs: calculated="
                            + calculated.get(mode) + ", expected=" + EXPECTED_ASCS.get(mode));
        }

        Config round1 = ValidateLiteratureBasedScoringCalibrationRound1Config
                .loadAndValidate(false);
        Config round2 = ConfigUtils.loadConfig(CONFIG.toString());
        validateRunControl(round2, requireOutputAbsent);
        validateAscs(round2);
        validateOnlyApprovedDifferences(round1, round2);
        require(round2.plans().getInputFile().equals(round1.plans().getInputFile()),
                "Round 2 must use the unchanged original input population");
        require(!round2.plans().getInputFile().toLowerCase(Locale.ROOT)
                        .contains("round-1"),
                "Round-1 output plans must not be used as input");
        ValidateLiteratureBasedScoringDiagnosticConfig.validateProtectedWorkspace();
        return round2;
    }

    static void validateRunControl(Config config, boolean requireOutputAbsent) {
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == LAST_ITERATION,
                "Round 2 must run iterations 0..60");
        require(RUN_ID.equals(config.controller().getRunId()), "Unexpected Round-2 run ID");
        require(OUTPUT.normalize().equals(
                        Path.of(config.controller().getOutputDirectory()).normalize()),
                "Unexpected Round-2 output directory");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Round-2 output must use failIfDirectoryExists");
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
        if (requireOutputAbsent) requireOutputAbsent(OUTPUT);
    }

    static void validateAscs(Config config) {
        for (var expected : EXPECTED_ASCS.entrySet()) {
            var params = config.scoring().getModes().get(expected.getKey());
            require(params != null && close(params.getConstant(), expected.getValue()),
                    "Unexpected " + expected.getKey() + " ASC");
        }
        require(config.scoring().getModes().get("walk").getConstant() == 0.0,
                "Walk must remain the exact zero reference");
    }

    static void validateOnlyApprovedDifferences(Config round1, Config round2) {
        String runId = round2.controller().getRunId();
        String output = round2.controller().getOutputDirectory();
        int last = round2.controller().getLastIteration();
        Map<String, Double> ascs = new LinkedHashMap<>();
        for (String mode : List.of("car", "pt", "bike", "walk")) {
            ascs.put(mode, round2.scoring().getModes().get(mode).getConstant());
        }
        round2.controller().setRunId(round1.controller().getRunId());
        round2.controller().setOutputDirectory(round1.controller().getOutputDirectory());
        round2.controller().setLastIteration(round1.controller().getLastIteration());
        for (String mode : ascs.keySet()) round2.scoring().getModes().get(mode)
                .setConstant(round1.scoring().getModes().get(mode).getConstant());
        List<String> differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(round1, round2);
        round2.controller().setRunId(runId);
        round2.controller().setOutputDirectory(output);
        round2.controller().setLastIteration(last);
        ascs.forEach((mode, value) -> round2.scoring().getModes().get(mode).setConstant(value));
        require(differences.isEmpty(), "Round 2 contains unapproved semantic differences:\n- "
                + String.join("\n- ", differences));
    }

    static Map<String, Double> readLateMeans(Path file) throws Exception {
        require(Files.isRegularFile(file), "Missing Round-1 late statistics: " + file);
        Map<String, Double> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            require(reader.readLine() != null, "Empty Round-1 late statistics");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                require("31-40".equals(fields[1]), "Round-2 basis must be iterations 31..40");
                result.put(fields[0], Double.parseDouble(fields[2]));
            }
        }
        return Map.copyOf(result);
    }

    static Map<String, Double> readRecommendedAscs(Path file) throws Exception {
        require(Files.isRegularFile(file), "Missing Round-1 ASC recommendation: " + file);
        Map<String, Double> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            require(reader.readLine() != null, "Empty Round-1 ASC recommendation");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                require(close(Double.parseDouble(fields[5]), 0.5),
                        "Round-1 recommendation damping must be 0.5");
                result.put(fields[0], Double.parseDouble(fields[7]));
            }
        }
        return Map.copyOf(result);
    }

    static AnalyzeLiteratureBasedScoringCalibrationRound1.RoundDefinition definition() {
        return new AnalyzeLiteratureBasedScoringCalibrationRound1.RoundDefinition(
                2, OUTPUT, RUN_ID, LAST_ITERATION, LATE_FIRST, LATE_LAST,
                "round_2", EXPECTED_ASCS, "ONE_FINAL_ASC_UPDATE_REQUIRED");
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
}
