package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Fail-closed preflight for the fixed Legacy-R1 final resident candidate. */
public final class ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig {
    public static final Path CONFIG = Path.of("scenarios/munich_calibration_2019/"
            + "config_resident_mode_choice_legacy_r1_final_candidate.xml");
    public static final Path OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "resident-mode-choice-legacy-r1-final-candidate");
    public static final String RUN_ID =
            "munich-calibration-2019-resident-legacy-r1-final-candidate";
    public static final Path LEGACY_REANALYSIS = Path.of(
            "scenarios/munich_calibration_2019/output/"
                    + "legacy-mode-choice-resident-reanalysis");
    public static final int LATE_FIRST_ITERATION = 51;
    public static final int LATE_LAST_ITERATION = 60;
    public static final int INNOVATION_DISABLE_AFTER_ITERATION = 48;
    public static final Map<String, Double> FIXED_CONSTANTS = ordered(
            0.0, 0.89, -0.21, 0.78);
    public static final Map<String, Double> LEGACY_R1_RESIDENT_SHARES = ordered(
            43.026028792, 21.752217537, 24.924385633, 10.297368038);
    public static final double LEGACY_R1_SUM_ABSOLUTE_DEVIATION = 31.900828850;
    static final Set<String> APPROVED_DIFFERENCES = Set.of(
            "module[controller]/@outputDirectory",
            "module[controller]/@runId",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][1]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][2]/@constant",
            "module[scoring]/set[scoringParameters][0]/set[modeParams][3]/@constant");

    private ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "F1A accepts no arguments");
        Config config = loadAndValidate();
        LegacyEvidence evidence = validateLegacyEvidence();
        System.out.printf(Locale.ROOT,
                "FINAL LEGACY-R1 RESIDENT CANDIDATE CONFIG VALIDATION PASS%n"
                        + "config=%s%noutput=%s%nconstants=%s%n"
                        + "legacyResidents=%d legacyResidentTrips=%d legacyShares=%s%n"
                        + "iterations=0..%d lateWindow=%d..%d "
                        + "innovationDisableAfter=%d qsimEnd=48:00:00%n"
                        + "No logarithmic update was calculated. No Controller or QSim "
                        + "was started.%n",
                CONFIG, OUTPUT, constants(config), evidence.residents(),
                evidence.residentTrips(), evidence.shares(),
                config.controller().getLastIteration(), LATE_FIRST_ITERATION,
                LATE_LAST_ITERATION, INNOVATION_DISABLE_AFTER_ITERATION);
    }

    static Config loadAndValidate() throws Exception {
        Config config = loadAndValidateStructure(true);
        AnalyzeMunichResidentCohort.Result cohort =
                ValidateResidentModeChoiceCalibrationConfig
                        .validateAuthoritativeCohort(config);
        require(cohort.residents()
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                "Authoritative Munich-resident count changed");
        require(cohort.residentMainTrips()
                        == ResidentModeChoiceCalibrationIterationListener
                        .EXPECTED_RESIDENT_MAIN_TRIPS,
                "Authoritative resident main-trip count changed");
        require(cohort.residentMainTrips() == 2 * cohort.residents(),
                "The synthetic resident population no longer has exactly two main trips "
                        + "per classified resident");
        validateExactlyTwoTripsPerResident(config);
        return config;
    }

    static Config loadAndValidateStructure(boolean requireOutputAbsent) throws Exception {
        validateLegacyEvidence();
        require(Files.isRegularFile(CONFIG), "Missing final-candidate config: " + CONFIG);
        Config baseline = ValidateResidentModeChoiceCalibrationRound4Config
                .loadAndValidateStructure(false);
        Config candidate = ConfigUtils.loadConfig(CONFIG.toString());
        Map<String, String> baselineSnapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(baseline);
        Map<String, String> candidateSnapshot =
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(candidate);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(baselineSnapshot, candidateSnapshot);
        require(differences.equals(APPROVED_DIFFERENCES),
                "Final candidate differs from the current 48-hour resident design "
                        + "outside run ID, output directory and fixed constants: "
                        + differences);
        validateApprovedValues(baseline, candidate);
        if (requireOutputAbsent) {
            ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(OUTPUT);
        }
        return candidate;
    }

    static LegacyEvidence validateLegacyEvidence() throws IOException {
        Path comparisonPath = LEGACY_REANALYSIS.resolve(
                "legacy_resident_mode_choice_comparison.csv");
        Path summaryPath = LEGACY_REANALYSIS.resolve(
                "legacy_round_1_resident_summary.csv");
        Path reportPath = LEGACY_REANALYSIS.resolve(
                "legacy_resident_reanalysis_report.md");
        Csv comparison = Csv.read(comparisonPath);
        Csv summary = Csv.read(summaryPath);
        require(Files.isRegularFile(reportPath),
                "Missing Legacy-R1 resident reanalysis report: " + reportPath);

        Map<String, String> r1 = unique(comparison, "calibration_run", "LEGACY_ROUND_1");
        require("true".equals(r1.get("available")), "Legacy Round 1 is unavailable");
        require("BOTH_INSIDE".equals(r1.get("original_calibration_cohort"))
                        && "MUNICH_RESIDENT_ALL_TRIPS".equals(
                        r1.get("new_comparison_cohort")),
                "Legacy Round-1 cohort provenance changed");
        require("YES_BEST_LEGACY_BY_SUM_ABSOLUTE_DEVIATION_REQUIRES_48H_RESIDENT_RERUN"
                        .equals(r1.get("candidate_for_one_final_48h_resident_validation")),
                "Legacy Round 1 is no longer the selected 48-hour candidate");
        requireLong(r1, "resident_persons", 68_770);
        requireLong(r1, "resident_main_trips", 137_540);
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            requireClose(r1, mode + "_constant", FIXED_CONSTANTS.get(mode), 1e-12);
            requireClose(r1, "resident_" + mode + "_share",
                    LEGACY_R1_RESIDENT_SHARES.get(mode), 1e-9);
        }
        requireClose(r1, "sum_absolute_modal_share_deviation_pp",
                LEGACY_R1_SUM_ABSOLUTE_DEVIATION, 1e-9);
        Map<String, String> r2 = unique(comparison, "calibration_run", "LEGACY_ROUND_2");
        require(number(r2, "sum_absolute_modal_share_deviation_pp")
                        > LEGACY_R1_SUM_ABSOLUTE_DEVIATION,
                "Legacy Round 1 no longer has the smaller legacy target deviation");

        requireSummary(summary, "cohort", "resident_persons", "all", 68_770);
        requireSummary(summary, "cohort", "resident_main_trips", "all", 137_540);
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            requireSummary(summary, "physical_mode", "trip_share", mode,
                    LEGACY_R1_RESIDENT_SHARES.get(mode));
        }
        requireSummary(summary, "territorial_breakdown", "resident_main_trips",
                "BOTH_INSIDE", 123_186);
        requireSummary(summary, "territorial_breakdown", "resident_main_trips",
                "ORIGIN_ONLY", 7_177);
        requireSummary(summary, "territorial_breakdown", "resident_main_trips",
                "DESTINATION_ONLY", 7_177);
        requireSummary(summary, "territorial_breakdown", "resident_main_trips",
                "BOTH_OUTSIDE", 0);

        String report = Files.readString(reportPath, StandardCharsets.UTF_8);
        require(report.contains("|LEGACY_ROUND_1|43.026028792%|21.752217537%|"
                        + "24.924385633%|10.297368038%|31.900828850 pp|")
                        && report.contains("Resident cohort: 68770 persons and 137540"),
                "Legacy resident report no longer contains the authoritative Round-1 "
                        + "selection evidence");
        return new LegacyEvidence(68_770, 137_540, LEGACY_R1_RESIDENT_SHARES,
                LEGACY_R1_SUM_ABSOLUTE_DEVIATION, comparisonPath, summaryPath,
                reportPath);
    }

    static Map<String, Double> constants(Config config) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            var parameters = config.scoring().getModes().get(mode);
            require(parameters != null, "Missing candidate scoring mode: " + mode);
            result.put(mode, parameters.getConstant());
        }
        return Map.copyOf(result);
    }

    static void validateExactlyTwoTripsPerResident(Config config) throws IOException {
        Path population = AnalyzeMunichResidentCohort.resolvePopulation(config);
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        MunichResidentClassifier classifier = new MunichResidentClassifier(boundary);
        long[] residents = {0};
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            if (classifier.classify(person).classification()
                    != MunichResidentClassifier.Classification.MUNICH_RESIDENT) return;
            int trips = TripStructureUtils.getTrips(person.getSelectedPlan(),
                    StageActivityTypeIdentifier::isStageActivity).size();
            require(trips == 2, "Munich resident " + person.getId()
                    + " has " + trips + " main trips instead of exactly two");
            residents[0]++;
        });
        reader.readFile(population.toString());
        require(residents[0] == ResidentCalibrationSubpopulations
                        .EXPECTED_MUNICH_RESIDENTS,
                "Exactly-two-trip validation found the wrong resident count: "
                        + residents[0]);
    }

    private static void validateApprovedValues(Config baseline, Config candidate) {
        require(RUN_ID.equals(candidate.controller().getRunId()),
                "Unexpected final-candidate run ID");
        require(normalize(OUTPUT).equals(normalize(
                        Path.of(candidate.controller().getOutputDirectory()))),
                "Unexpected final-candidate output directory");
        require(candidate.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting
                        .failIfDirectoryExists,
                "Final-candidate output must remain fail-if-exists protected");
        require(candidate.controller().getFirstIteration() == 0
                        && candidate.controller().getLastIteration() == 60,
                "Final candidate must run exactly iterations 0..60");
        require(candidate.global().getRandomSeed() == 4711,
                "Final-candidate random seed changed");
        require(candidate.qsim().getEndTime().isDefined()
                        && Math.abs(candidate.qsim().getEndTime().seconds()
                        - 48 * 3600.0) < 1e-9,
                "Final-candidate QSim horizon must be 48:00:00");
        require(Math.abs(candidate.replanning()
                        .getFractionOfIterationsToDisableInnovation() - 0.8) < 1e-12,
                "Final-candidate innovation-disable fraction changed");
        require(candidate.replanning().getMaxAgentPlanMemorySize() == 4,
                "Final-candidate plan memory changed");
        require(INNOVATION_DISABLE_AFTER_ITERATION == Math.floor(0.8 * 60),
                "Innovation-disable iteration is not 48");
        require(baseline.plans().getInputFile().equals(
                        candidate.plans().getInputFile())
                        && "../munich_base_2023/munich-v1.0-5pct.plans.xml".equals(
                        candidate.plans().getInputFile()),
                "Final candidate must reload the unchanged original population");
        String plans = candidate.plans().getInputFile().replace('\\', '/');
        require(!plans.contains("output/") && !plans.contains("mode-choice-round-1"),
                "Final candidate must not load Legacy Round-1 final plans");
        require(constants(candidate).equals(FIXED_CONSTANTS),
                "Final-candidate constants changed: " + constants(candidate));
        require(ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.equals(
                        Map.of("car", 34.0, "pt", 24.0,
                                "bike", 18.0, "walk", 24.0)),
                "Resident trip-share targets changed");
        ResidentModeChoiceCalibrationTargets.validate();
        require(!normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationConfig.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(
                        ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT))
                        && !normalize(OUTPUT).equals(normalize(LEGACY_REANALYSIS)),
                "Final-candidate output collides with protected evidence");
    }

    private static void requireSummary(Csv csv, String section, String metric,
                                       String dimension, double expected) {
        List<Map<String, String>> rows = csv.rows().stream()
                .filter(row -> section.equals(row.get("section"))
                        && metric.equals(row.get("metric"))
                        && dimension.equals(row.get("dimension"))).toList();
        require(rows.size() == 1, "Expected one Legacy-R1 summary row for "
                + section + "/" + metric + "/" + dimension);
        double tolerance = "trip_share".equals(metric) ? 1e-9 : 1e-6;
        require(Math.abs(number(rows.getFirst(), "value") - expected) <= tolerance,
                "Legacy-R1 summary value changed for " + metric + "/" + dimension);
    }

    private static Map<String, String> unique(Csv csv, String field, String value) {
        List<Map<String, String>> rows = csv.rows().stream()
                .filter(row -> value.equals(row.get(field))).toList();
        require(rows.size() == 1,
                "Expected one " + field + "=" + value + " evidence row");
        return rows.getFirst();
    }

    private static void requireLong(Map<String, String> row, String field, long expected) {
        require(Math.round(number(row, field)) == expected,
                "Legacy evidence changed for " + field);
    }

    private static void requireClose(Map<String, String> row, String field,
                                     double expected, double tolerance) {
        require(Math.abs(number(row, field) - expected) <= tolerance,
                "Legacy evidence changed for " + field + ": " + row.get(field));
    }

    private static double number(Map<String, String> row, String field) {
        String value = row.get(field);
        require(value != null && !value.isBlank(), "Missing legacy evidence field " + field);
        return Double.parseDouble(value);
    }

    private static Map<String, Double> ordered(double car, double pt,
                                                double bike, double walk) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<>();
        values.put("car", car);
        values.put("pt", pt);
        values.put("bike", bike);
        values.put("walk", walk);
        return Map.copyOf(values);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }

    record LegacyEvidence(long residents, long residentTrips,
                          Map<String, Double> shares, double sumAbsoluteDeviation,
                          Path comparison, Path round1Summary, Path report) { }

    private record Csv(List<Map<String, String>> rows) {
        static Csv read(Path path) throws IOException {
            require(Files.isRegularFile(path), "Missing required legacy evidence: " + path);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            require(!lines.isEmpty(), "Empty legacy evidence: " + path);
            List<String> header = List.of(lines.getFirst().split(",", -1));
            ArrayList<Map<String, String>> rows = new ArrayList<>();
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).isBlank()) continue;
                String[] fields = lines.get(line).split(",", -1);
                require(fields.length == header.size(),
                        "Malformed legacy CSV row " + (line + 1) + " in " + path);
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                for (int column = 0; column < fields.length; column++) {
                    row.put(header.get(column), fields[column]);
                }
                rows.add(Map.copyOf(row));
            }
            return new Csv(List.copyOf(rows));
        }
    }
}
