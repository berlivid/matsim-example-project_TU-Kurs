package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;

/** Standalone read-only selected-plan analysis of the protected initial output. */
public final class AnalyzeInitial2019ResidentModeChoiceOutput {
    private AnalyzeInitial2019ResidentModeChoiceOutput() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This analyzer accepts no arguments and cannot select another output");
        Path output = ValidateResidentModeChoiceCalibrationConfig.OUTPUT;
        Config config = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        OutputAnalysis outputAnalysis = analyzeOutput(config, output);
        System.out.printf("RESIDENT MODE-CHOICE OUTPUT ANALYSIS %s%noutput=%s%nplans=%s%nevents=%s%n"
                        + "residentPersons=%d residentTrips=%d affectedResidentTrips=%d%n",
                outputAnalysis.sensitivity().status(), output, outputAnalysis.plans(),
                outputAnalysis.events(), outputAnalysis.residentPersons(),
                outputAnalysis.residentTrips(),
                outputAnalysis.sensitivity().affectedMainTrips());
    }

    static OutputAnalysis analyzeOutput(Config config, Path output) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(output),
                "Resident calibration output does not exist: " + output);
        Path plans = finalPlans(output);
        config.plans().setInputFile(plans.toAbsolutePath().normalize().toString());
        Scenario scenario = ScenarioUtils.loadScenario(config);
        ResidentCalibrationSubpopulations.assignAndValidate(
                scenario.getPopulation(), MunichMunicipalBoundary.loadDefault());
        var residentPlans = ResidentModeChoiceCalibrationIterationListener
                .selectedResidentPlanSnapshot(scenario);
        ModeChoiceCalibrationAnalysis analysis = new ModeChoiceCalibrationAnalysis(
                scenario, MunichMunicipalBoundary.loadDefault());
        var result = analysis.analyze(config.controller().getLastIteration(), residentPlans);
        ResidentModeChoiceCalibrationIterationListener.validateResidentStructure(
                result, residentPlans.size(), config.controller().getLastIteration());

        Path events = finalIterationEvents(output, config.controller().getLastIteration());
        ResidentStuckMainTripTracker tracker = new ResidentStuckMainTripTracker(scenario);
        var eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(tracker);
        new MatsimEventsReader(eventsManager).readFile(events.toString());
        ResidentStuckMainTripTracker.Snapshot stuck = tracker.snapshot();
        var sensitivity = analysis.analyze(config.controller().getLastIteration(),
                residentPlans, stuck.affectedMainTrips());
        new ResidentModeChoiceCalibrationAnalysisWriter(output).writeStandaloneFinal(result);
        var sensitivityResult = new ResidentModeChoiceStuckSensitivityWriter(output)
                .write(result, sensitivity, stuck);
        long residentTrips = result.metrics(
                ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS).mainTrips();
        return new OutputAnalysis(plans, events, residentPlans.size(), residentTrips,
                sensitivityResult);
    }

    static Path finalPlans(Path output) throws Exception {
        try (var paths = Files.walk(output)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".output_plans.xml.gz"))
                    .sorted().toList();
            ValidateModeChoiceCalibrationConfig.require(candidates.size() == 1,
                    "Expected exactly one final output plans file in " + output
                            + ", found " + candidates);
            return candidates.getFirst();
        }
    }

    static Path finalIterationEvents(Path output, int iteration) throws Exception {
        Path iterationDirectory = output.resolve("ITERS").resolve("it." + iteration);
        ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(iterationDirectory),
                "Final iteration directory does not exist: " + iterationDirectory);
        try (var paths = Files.walk(iterationDirectory)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith("." + iteration + ".events.xml.gz"))
                    .sorted().toList();
            ValidateModeChoiceCalibrationConfig.require(candidates.size() == 1,
                    "Expected exactly one final-iteration events file in "
                            + iterationDirectory + ", found " + candidates);
            return candidates.getFirst();
        }
    }

    record OutputAnalysis(Path plans, Path events, long residentPersons, long residentTrips,
                          ResidentModeChoiceStuckSensitivityWriter.Result sensitivity) { }
}
