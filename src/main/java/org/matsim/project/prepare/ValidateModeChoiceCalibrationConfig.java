package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.pt.config.TransitConfigGroup;

/** Read-only, fail-closed validation of the separate synthetic-2019 calibration config. */
public final class ValidateModeChoiceCalibrationConfig {
    public static final Path CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_mode_choice_calibration.xml");
    public static final Path INPUT_VALIDATION_CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_input_validation.xml");
    public static final List<String> OFFERED_MODES = List.of("car", "pt", "walk", "bike");
    public static final Set<String> CHAIN_BASED_MODES = Set.of("car", "bike");
    public static final Map<String, Double> STRATEGY_WEIGHTS = Map.of(
            "ChangeExpBeta", 0.8,
            "ReRoute", 0.1,
            "SubtourModeChoice", 0.1);

    private ValidateModeChoiceCalibrationConfig() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "This read-only validator accepts no arguments");
        Config config = loadAndValidate();
        System.out.printf(Locale.ROOT,
                "MODE-CHOICE CONFIG VALIDATION PASS%nconfig=%s%nmodes=%s%nstrategies=%s%n"
                        + "iterations=%d..%d seed=%d capacity=%.2f/%.2f output=%s%n",
                CONFIG, Arrays.toString(config.subtourModeChoice().getModes()),
                strategyMap(config), config.controller().getFirstIteration(),
                config.controller().getLastIteration(), config.global().getRandomSeed(),
                config.qsim().getFlowCapFactor(), config.qsim().getStorageCapFactor(),
                config.controller().getOutputDirectory());
    }

    public static Config loadAndValidate() throws IOException {
        require(Files.isRegularFile(CONFIG), "Missing calibration config: " + CONFIG);
        require(Files.isRegularFile(INPUT_VALIDATION_CONFIG),
                "Missing iteration-zero validation config: " + INPUT_VALIDATION_CONFIG);

        String xml = Files.readString(CONFIG);
        require(moduleCount(xml, "replanning") == 1,
                "Calibration XML must contain exactly one replanning module");
        require(moduleCount(xml, "strategy") == 0,
                "Legacy strategy modules are forbidden");

        Config config = ConfigUtils.loadConfig(CONFIG.toString());
        Config inputValidation = ConfigUtils.loadConfig(INPUT_VALIDATION_CONFIG.toString());
        validateInputs(config, inputValidation, xml);
        validateTransit(config);
        validateReplanning(config);
        validateSubtourModeChoice(config);
        validateScoring(config);
        validateRunSettings(config);
        validateTargetSchema();
        return config;
    }

    private static void validateInputs(Config config, Config inputValidation, String xml) {
        require("EPSG:31468".equals(config.global().getCoordinateSystem()),
                "Unexpected coordinate system");
        require(sameInput(config.network().getInputFile(),
                        inputValidation.network().getInputFile()),
                "Calibration network differs from the validated synthetic-2019 input");
        require(sameInput(config.plans().getInputFile(), inputValidation.plans().getInputFile()),
                "Calibration population differs from the original public 5-% population");
        require(sameInput(config.transit().getTransitScheduleFile(),
                        inputValidation.transit().getTransitScheduleFile()),
                "Calibration schedule differs from the validated synthetic-2019 input");
        require(sameInput(config.transit().getVehiclesFile(),
                        inputValidation.transit().getVehiclesFile()),
                "Calibration vehicles differ from the validated synthetic-2019 input");

        requireInputExists(config.network().getInputFile(), "network");
        requireInputExists(config.plans().getInputFile(), "population");
        requireInputExists(config.transit().getTransitScheduleFile(), "transit schedule");
        requireInputExists(config.transit().getVehiclesFile(), "transit vehicles");

        String lower = xml.toLowerCase(Locale.ROOT);
        require(!lower.contains("munich_bau_2040") && !lower.contains("munich_fast_track_2040")
                        && !lower.contains("config_bau") && !lower.contains("config_fast_track"),
                "Calibration config refers to a BAU or Fast Track input");
        require(!lower.contains("munich_boundary") && !lower.contains("boundary_filter"),
                "The analysis-only municipal-boundary filter must not alter simulation demand");
    }

    private static void validateTransit(Config config) {
        require(config.transit().isUseTransit(), "useTransit must be true");
        require(config.transit().isUsingTransitInMobsim(),
                "Transit must be simulated in the mobility simulation");
        require(config.transit().getRoutingAlgorithmType()
                        == TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor,
                "SwissRailRaptor is not configured");
    }

    private static void validateReplanning(Config config) {
        require(config.replanning().getMaxAgentPlanMemorySize() == 4,
                "maxAgentPlanMemorySize must be 4");
        require("WorstPlanSelector".equals(config.replanning().getPlanSelectorForRemoval()),
                "Unexpected plan selector for removal");
        require(close(config.replanning().getFractionOfIterationsToDisableInnovation(), 0.8),
                "Innovation-disable fraction must be 0.8");
        require(config.replanning().getStrategySettings().size() == 3,
                "Exactly three replanning strategies are required");
        Map<String, Double> actual = strategyMap(config);
        require(actual.equals(STRATEGY_WEIGHTS), "Unexpected strategies or weights: " + actual);
        require(close(actual.values().stream().mapToDouble(Double::doubleValue).sum(), 1.0),
                "Strategy weights do not sum to 1.0");
    }

    private static void validateSubtourModeChoice(Config config) {
        require(Arrays.asList(config.subtourModeChoice().getModes()).equals(OFFERED_MODES),
                "Only car, pt, walk and bike may be offered");
        require(Set.of(config.subtourModeChoice().getChainBasedModes())
                        .equals(CHAIN_BASED_MODES),
                "Only car and bike must be chain based");
        require("fromSpecifiedModesToSpecifiedModes".equals(
                        config.subtourModeChoice().getBehavior().toString()),
                "Unexpected SubtourModeChoice behavior");
        require(!config.subtourModeChoice().considerCarAvailability(),
                "considerCarAvailability must remain false until attributes exist");
        require(close(config.subtourModeChoice().getProbaForRandomSingleTripMode(), 0.0),
                "Random single-trip mode choice must be disabled");
        require(close(config.subtourModeChoice().getCoordDistance(), 0.0),
                "Subtour coordinate tolerance must be 0");
    }

    private static void validateScoring(Config config) {
        for (String mode : OFFERED_MODES) {
            var params = config.scoring().getModes().get(mode);
            require(params != null, "Missing scoring parameters for " + mode);
            require(close(params.getConstant(), 0.0),
                    "Initial " + mode + " constant is not zero");
            require(close(params.getMarginalUtilityOfTraveling(), -6.0),
                    "Travel-time utility changed for " + mode);
            require(close(params.getMarginalUtilityOfDistance(), 0.0)
                            && close(params.getMonetaryDistanceRate(), 0.0),
                    "Distance or monetary value changed for " + mode);
        }
        require(config.scoring().getModes().containsKey("ride")
                        && config.scoring().getModes().containsKey("other"),
                "Technical ride/other scoring parameters must be retained");
        require(close(config.scoring().getPerforming_utils_hr(), 6.0)
                        && close(config.scoring().getMarginalUtlOfWaitingPt_utils_hr(), -6.0)
                        && close(config.scoring().getUtilityOfLineSwitch(), -1.0),
                "Common scoring values changed");
        require(config.scoring().isWriteExperiencedPlans(),
                "Experienced plans must be written for reproducible final analysis");
        require(close(config.routing().getTeleportedModeParams().get("walk")
                        .getTeleportedModeSpeed(), 0.8333333333333333)
                        && close(config.routing().getTeleportedModeParams().get("bike")
                        .getTeleportedModeSpeed(), 4.166666666666667),
                "Walk or bike teleportation speed changed");
    }

    private static void validateRunSettings(Config config) {
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == 20,
                "Diagnostic iterations must be 0..20");
        require(config.global().getRandomSeed() == 4711, "Random seed must be 4711");
        require(config.global().getNumberOfThreads() == 4,
                "Global thread count must be 4");
        require(config.qsim().getNumberOfThreads() == 2,
                "QSim thread count must be 2");
        require(close(config.qsim().getFlowCapFactor(), 0.05)
                        && close(config.qsim().getStorageCapFactor(), 0.05),
                "QSim capacity factors must both be 0.05");
        require(config.qsim().getEndTime().isDefined()
                        && close(config.qsim().getEndTime().seconds(), 43 * 3_600),
                "Validated synthetic-2019 horizon must remain 43:00:00");
        require("munich-calibration-2019-initial".equals(config.controller().getRunId()),
                "Unexpected calibration runId");
        require("scenarios/munich_calibration_2019/output/mode-choice-initial"
                        .equals(config.controller().getOutputDirectory()),
                "Unexpected calibration output directory");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Calibration output must fail if its directory exists");
    }

    private static void validateTargetSchema() throws IOException {
        var targets = ModeChoiceCalibrationTargets.read(
                ModeChoiceCalibrationTargets.DEFAULT_FILE);
        require(targets.size() == 20, "Expected 20 versioned target/reference rows");
        require(targets.stream().map(target -> target.metric() + "|" + target.mode())
                        .distinct().count() == targets.size(),
                "Target metric/mode rows must be unique");
        double tripShare = targets.stream()
                .filter(target -> "trip_modal_share".equals(target.metric()))
                .map(ModeChoiceCalibrationTargets.Target::numericValue)
                .mapToDouble(Double::doubleValue).sum();
        require(close(tripShare, 100.0), "Four-mode trip-share targets must sum to 100%");
        double pkmShare = targets.stream()
                .filter(target -> "annual_pkm_share".equals(target.metric()))
                .map(ModeChoiceCalibrationTargets.Target::numericValue)
                .mapToDouble(Double::doubleValue).sum();
        require(close(pkmShare, 100.0), "Derived annual Pkm shares must sum to 100%");
        var occupancy = targets.stream()
                .filter(target -> "car_occupancy_factor".equals(target.metric()))
                .findFirst().orElseThrow();
        require(close(occupancy.numericValue(), 1.5),
                "Car occupancy reference must equal supplied Pkm/Fkm ratio 1.5");
    }

    static Map<String, Double> strategyMap(Config config) {
        return config.replanning().getStrategySettings().stream().collect(Collectors.toUnmodifiableMap(
                ReplanningConfigGroup.StrategySettings::getStrategyName,
                ReplanningConfigGroup.StrategySettings::getWeight));
    }

    private static int moduleCount(String xml, String name) {
        Pattern pattern = Pattern.compile("<module\\s+name\\s*=\\s*[\\\"']"
                + Pattern.quote(name) + "[\\\"']", Pattern.CASE_INSENSITIVE);
        return (int) pattern.matcher(xml).results().count();
    }

    private static boolean sameInput(String first, String second) {
        return normalizeInput(first).equals(normalizeInput(second));
    }

    private static String normalizeInput(String input) {
        return input.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static void requireInputExists(String input, String description) {
        Path path = CONFIG.getParent().resolve(input).normalize();
        require(Files.isRegularFile(path), "Missing " + description + ": " + path);
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 1e-12;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
