package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.pt.config.TransitConfigGroup;

/** Read-only, fail-closed validator for the productive resident calibration. */
public final class ValidateResidentModeChoiceCalibrationConfig {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_resident_mode_choice_calibration.xml");
    public static final Path OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/resident-mode-choice-initial");
    private static final Map<String, Map<String, Double>> EXPECTED_STRATEGIES = Map.of(
            ResidentCalibrationSubpopulations.MUNICH_RESIDENT, Map.of(
                    "ChangeExpBeta", 0.8, "ReRoute", 0.1, "SubtourModeChoice", 0.1),
            ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND, Map.of(
                    "ChangeExpBeta", 0.9, "ReRoute", 0.1),
            ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND, Map.of(
                    "ChangeExpBeta", 0.9, "ReRoute", 0.1));
    private static final Map<String, String> PROTECTED_INPUT_SHA256 = Map.of(
            "../munich_base_2023/munich-v1.0-5pct.plans.xml",
            "DCC920DBD6158D898C7C774F77A5E9BE35DD7E7135150EE66FAA1FC9D34139CB",
            "input_transit/network-with-pt.xml.gz",
            "68FD7ABA2AD7DC459AC4B21672C426E0E4560994449BFE968278F0804CD6D86F",
            "input_transit/transitSchedule.xml.gz",
            "5F247C33D43C31A24CE8ABC8DCB1731A30E58257CA37BC16AE5AA1C1269CF55E",
            "input_transit/transitVehicles.xml.gz",
            "EA3EC382AB1A5A003B9F5C17EA1846BB4F4ECD89BD3BFC360D2C9ED321B16EE1");

    private ValidateResidentModeChoiceCalibrationConfig() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only validator accepts no arguments");
        Config config = loadAndValidate();
        System.out.printf(Locale.ROOT,
                "RESIDENT MODE-CHOICE CONFIG VALIDATION PASS%nconfig=%s%n"
                        + "population=%s%nsubpopulations=%d/%d/%d%n"
                        + "residentTrips=%d strategies=%s output=%s%nNo QSim or controller was started.%n",
                CONFIG, AnalyzeMunichResidentCohort.resolvePopulation(config),
                ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                ResidentModeChoiceCalibrationIterationListener.EXPECTED_RESIDENT_MAIN_TRIPS,
                strategyMap(config), OUTPUT);
    }

    public static Config loadAndValidate() throws Exception {
        Config config = loadAndValidateStructure(true);
        validateAuthoritativeCohort(config);
        return config;
    }

    static Config loadAndValidateStructure(boolean requireOutputAbsent) throws Exception {
        require(Files.isRegularFile(CONFIG), "Missing resident calibration config: " + CONFIG);
        String xml = Files.readString(CONFIG);
        Config config = ConfigUtils.loadConfig(CONFIG.toString());
        validateInputs(config);
        validateRunSettings(config, requireOutputAbsent);
        validateStrategies(config);
        validateSubtourModeChoice(config, xml);
        validateScoring(config);
        ResidentModeChoiceCalibrationTargets.validate();
        return config;
    }

    static AnalyzeMunichResidentCohort.Result validateAuthoritativeCohort(Config config)
            throws IOException {
        Path population = AnalyzeMunichResidentCohort.resolvePopulation(config);
        AnalyzeMunichResidentCohort.Result result = AnalyzeMunichResidentCohort.analyze(
                population, MunichMunicipalBoundary.loadDefault());
        require(result.persons() == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                "Authoritative population count changed: " + result.persons());
        require(result.selectedPlans() == ResidentCalibrationSubpopulations.EXPECTED_TOTAL_PERSONS,
                "Selected-plan count changed: " + result.selectedPlans());
        require(result.residents() == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS,
                "Munich-resident cohort changed: " + result.residents());
        require(result.nonResidents()
                        == ResidentCalibrationSubpopulations.EXPECTED_REGIONAL_BACKGROUND,
                "Regional-background cohort changed: " + result.nonResidents());
        require(result.unresolvedPersons()
                        == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                "Unresolved-background cohort changed: " + result.unresolvedPersons());
        require(result.classification(
                        MunichResidentClassifier.Classification.NO_HOME_ACTIVITY)
                        == ResidentCalibrationSubpopulations.EXPECTED_UNRESOLVED_BACKGROUND,
                "The authoritative unresolved cohort is no longer exactly the no-home cohort");
        for (var classification : List.of(
                MunichResidentClassifier.Classification.MISSING_HOME_COORDINATE,
                MunichResidentClassifier.Classification.CONFLICTING_HOME_LOCATIONS,
                MunichResidentClassifier.Classification.INVALID_SELECTED_PLAN)) {
            require(result.classification(classification) == 0,
                    "Unexpected unresolved classification " + classification + ": "
                            + result.classification(classification));
        }
        require(result.residentMainTrips()
                        == ResidentModeChoiceCalibrationIterationListener.EXPECTED_RESIDENT_MAIN_TRIPS,
                "Resident main-trip count changed: " + result.residentMainTrips());
        requireScope(result, MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE,
                ResidentModeChoiceCalibrationIterationListener.EXPECTED_BOTH_INSIDE);
        requireScope(result, MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY,
                ResidentModeChoiceCalibrationIterationListener.EXPECTED_ORIGIN_ONLY);
        requireScope(result, MunichTripBoundaryFilter.SpatialCategory.DESTINATION_ONLY,
                ResidentModeChoiceCalibrationIterationListener.EXPECTED_DESTINATION_ONLY);
        requireScope(result, MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE, 0);
        requireScope(result,
                MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE, 0);
        require(result.spatialCategorySum() == result.residentMainTrips(),
                "All resident trips must enter exactly one spatial category");
        require(result.residentsWithClosedSubtour()
                        == ResidentCalibrationSubpopulations.EXPECTED_MUNICH_RESIDENTS
                        && result.residentsWithoutClosedSubtour() == 0,
                "Not every Munich resident is closed-subtour capable");
        return result;
    }

    static Map<String, Map<String, Double>> strategyMap(Config config) {
        LinkedHashMap<String, Map<String, Double>> result = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, Double>> mutable = new LinkedHashMap<>();
        for (ReplanningConfigGroup.StrategySettings setting
                : config.replanning().getStrategySettings()) {
            String subpopulation = setting.getSubpopulation();
            require(subpopulation != null && !subpopulation.isBlank(),
                    "Unscoped/default replanning strategy is forbidden: "
                            + setting.getStrategyName());
            Double previous = mutable.computeIfAbsent(subpopulation,
                            ignored -> new LinkedHashMap<>())
                    .put(setting.getStrategyName(), setting.getWeight());
            require(previous == null,
                    "Duplicate strategy for " + subpopulation + ": "
                            + setting.getStrategyName());
        }
        mutable.forEach((key, value) -> result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }

    static void requireOutputAbsent(Path output) {
        require(!Files.exists(output),
                "Resident calibration output already exists; nothing was deleted: " + output);
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(file)), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    static Map<Path, String> protectedInputExpectations() {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        PROTECTED_INPUT_SHA256.forEach((relative, hash) -> result.put(
                CONFIG.getParent().resolve(relative).normalize(), hash));
        result.put(MunichMunicipalBoundary.DEFAULT_FILE.normalize(),
                "EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26");
        return Map.copyOf(result);
    }

    static Map<Path, String> captureProtectedInputHashes() throws Exception {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        for (Path path : protectedInputExpectations().keySet()) {
            require(Files.isRegularFile(path), "Protected input is missing: " + path);
            result.put(path, path.equals(MunichMunicipalBoundary.DEFAULT_FILE.normalize())
                    ? MunichMunicipalBoundary.canonicalTextSha256(path)
                    : sha256(path));
        }
        result.put(CONFIG.normalize(), sha256(CONFIG));
        return Map.copyOf(result);
    }

    private static void validateInputs(Config config) throws Exception {
        require("EPSG:31468".equals(config.global().getCoordinateSystem()),
                "Unexpected calibration coordinate system");
        require("../munich_base_2023/munich-v1.0-5pct.plans.xml".equals(
                        config.plans().getInputFile()),
                "Unexpected resident calibration population");
        require("input_transit/network-with-pt.xml.gz".equals(
                        config.network().getInputFile()),
                "Unexpected resident calibration network");
        require("input_transit/transitSchedule.xml.gz".equals(
                        config.transit().getTransitScheduleFile()),
                "Unexpected resident calibration transit schedule");
        require("input_transit/transitVehicles.xml.gz".equals(
                        config.transit().getVehiclesFile()),
                "Unexpected resident calibration transit vehicles");
        require(config.transit().isUseTransit(), "Transit must remain enabled");
        require(config.transit().getRoutingAlgorithmType()
                        == TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor,
                "SwissRailRaptor must remain configured");
        for (var input : PROTECTED_INPUT_SHA256.entrySet()) {
            Path path = CONFIG.getParent().resolve(input.getKey()).normalize();
            require(Files.isRegularFile(path), "Protected input is missing: " + path);
            require(input.getValue().equals(sha256(path)),
                    "Protected input hash changed: " + path);
        }
        require(MunichMunicipalBoundary.loadDefault().sha256().equals(
                        "EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26"),
                "Munich municipal boundary hash changed");
    }

    private static void validateRunSettings(Config config, boolean checkOutput) {
        require("munich-calibration-2019-resident-initial".equals(
                        config.controller().getRunId()),
                "Unexpected resident calibration runId");
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == 20,
                "Resident calibration must run iterations 0..20");
        require(config.global().getRandomSeed() == 4711,
                "Resident calibration random seed changed");
        require(close(config.qsim().getFlowCapFactor(), 0.05)
                        && close(config.qsim().getStorageCapFactor(), 0.05),
                "Resident calibration capacity factors must remain 5%");
        require(config.qsim().getEndTime().isDefined()
                        && close(config.qsim().getEndTime().seconds(), 43 * 3600.0),
                "Resident calibration qsim horizon must remain 43 hours");
        require(OUTPUT.toString().replace('\\', '/').equals(
                        config.controller().getOutputDirectory().replace('\\', '/')),
                "Unexpected protected resident output path");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Resident output must fail if its directory exists");
        if (checkOutput) requireOutputAbsent(OUTPUT);
    }

    private static void validateStrategies(Config config) {
        Map<String, Map<String, Double>> actual = strategyMap(config);
        require(actual.equals(EXPECTED_STRATEGIES),
                "Unexpected strategies by runtime subpopulation: " + actual);
        for (var entry : actual.entrySet()) {
            require(close(entry.getValue().values().stream()
                            .mapToDouble(Double::doubleValue).sum(), 1.0),
                    "Strategy weights do not sum to 1.0 for " + entry.getKey());
        }
        require(config.replanning().getMaxAgentPlanMemorySize() == 4,
                "Unexpected maximum plan memory");
        require("WorstPlanSelector".equals(config.replanning().getPlanSelectorForRemoval()),
                "Unexpected plan-removal selector");
        require(close(config.replanning().getFractionOfIterationsToDisableInnovation(), 0.8),
                "Unexpected innovation-disable fraction");
        long residentSubtour = config.replanning().getStrategySettings().stream()
                .filter(setting -> "SubtourModeChoice".equals(setting.getStrategyName()))
                .filter(setting -> ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                        setting.getSubpopulation())).count();
        require(residentSubtour == 1,
                "SubtourModeChoice must exist exactly once for Munich residents");
        require(config.replanning().getStrategySettings().stream()
                        .filter(setting -> !ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                                setting.getSubpopulation()))
                        .noneMatch(setting -> isModeChanging(setting.getStrategyName())),
                "A background group could receive a mode-changing strategy");
    }

    private static void validateSubtourModeChoice(Config config, String xml) {
        require(Arrays.equals(config.subtourModeChoice().getModes(),
                        new String[]{"car", "pt", "walk", "bike"}),
                "Unexpected SubtourModeChoice alternatives");
        require(Set.of(config.subtourModeChoice().getChainBasedModes())
                        .equals(Set.of("car", "bike")),
                "Unexpected chain-based modes");
        require("fromSpecifiedModesToSpecifiedModes".equals(
                        config.subtourModeChoice().getBehavior().toString()),
                "Rejected Open-Tour behavior or another unstable behavior is configured");
        require(!config.subtourModeChoice().considerCarAvailability(),
                "considerCarAvailability must remain false");
        require(close(config.subtourModeChoice().getProbaForRandomSingleTripMode(), 0.0),
                "Random single-trip Open-Tour behavior must remain disabled");
        require(close(config.subtourModeChoice().getCoordDistance(), 0.0),
                "Subtour closure must retain the stable configured behavior");
        String lower = xml.toLowerCase(Locale.ROOT);
        require(!lower.contains("betweenallandfewerconstraints")
                        && !lower.contains("open_tour") && !lower.contains("open-tour"),
                "Rejected Open-Tour configuration marker found");
    }

    private static void validateScoring(Config config) {
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            require(config.scoring().getModes().containsKey(mode),
                    "Missing scoring mode " + mode);
            require(close(config.scoring().getModes().get(mode).getConstant(), 0.0),
                    "Initial mode constant must be zero for " + mode);
        }
        require(!config.scoring().isWriteExperiencedPlans(),
                "ExperiencedPlansService output is not part of the resident pipeline");
    }

    private static boolean isModeChanging(String strategy) {
        if (strategy == null) return false;
        String normalized = strategy.toLowerCase(Locale.ROOT);
        return normalized.contains("modechoice") || normalized.contains("changetripmode")
                || normalized.contains("changesingletripmode");
    }

    private static void requireScope(AnalyzeMunichResidentCohort.Result result,
                                     MunichTripBoundaryFilter.SpatialCategory category,
                                     long expected) {
        long actual = result.residentTripsByScope().getOrDefault(category, 0L);
        require(actual == expected,
                "Resident " + category + " trip count changed: " + actual
                        + " != " + expected);
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) <= 1e-12;
    }

    private static void require(boolean condition, String message) {
        ValidateModeChoiceCalibrationConfig.require(condition, message);
    }
}
