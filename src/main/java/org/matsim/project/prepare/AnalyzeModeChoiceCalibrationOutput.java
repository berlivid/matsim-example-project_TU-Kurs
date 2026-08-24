package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only postprocessor for a completed synthetic-2019 calibration output. */
public final class AnalyzeModeChoiceCalibrationOutput {
    public static final Path DEFAULT_OUTPUT = Path.of(
            "scenarios/munich_calibration_2019/output/mode-choice-initial");

    private AnalyzeModeChoiceCalibrationOutput() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length <= 1,
                "Usage: AnalyzeModeChoiceCalibrationOutput [output-directory]");
        Path output = args.length == 0 ? DEFAULT_OUTPUT : Path.of(args[0]);
        analyze(output);
    }

    static void analyze(Path output) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(output),
                "Calibration output directory is missing: " + output);
        Path experiencedPlans = findExperiencedPlans(output);

        Config config = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.CONFIG.toString());
        config.plans().setInputFile(experiencedPlans.toAbsolutePath().normalize().toString());
        var scenario = ScenarioUtils.loadScenario(config);
        Map<Id<Person>, Plan> plans = new java.util.TreeMap<>();
        scenario.getPopulation().getPersons().forEach((id, person) -> {
            if (person.getSelectedPlan() != null) plans.put(id, person.getSelectedPlan());
        });
        ValidateModeChoiceCalibrationConfig.require(!plans.isEmpty(),
                "Experienced-plans file contains no selected plans");

        ModeChoiceCalibrationAnalysis analysis = new ModeChoiceCalibrationAnalysis(
                scenario, MunichMunicipalBoundary.loadDefault());
        var result = analysis.analyze(config.controller().getLastIteration(), plans);
        new ModeChoiceCalibrationAnalysisWriter(output).write(java.util.List.of(result), true);
        System.out.printf("MODE-CHOICE OUTPUT ANALYSIS PASS%noutput=%s%nplans=%d iteration=%d%n",
                output, plans.size(), result.iteration());
    }

    private static Path findExperiencedPlans(Path output) throws IOException {
        try (var files = Files.list(output)) {
            var matches = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".output_experienced_plans.xml.gz"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            ValidateModeChoiceCalibrationConfig.require(matches.size() == 1,
                    "Expected exactly one output_experienced_plans.xml.gz file in " + output
                            + "; found " + matches.size());
            return matches.getFirst();
        }
    }
}
