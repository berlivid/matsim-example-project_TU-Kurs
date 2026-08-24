package org.matsim.project.prepare;

import com.google.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.scoring.ExperiencedPlansService;

/** Analyzes executed plans after mobsim and before the iteration's replanning step. */
public final class ModeChoiceCalibrationIterationListener implements AfterMobsimListener {
    private final Scenario scenario;
    private final ExperiencedPlansService experiencedPlans;
    private final ModeChoiceCalibrationAnalysis analysis;
    private final ModeChoiceCalibrationAnalysisWriter writer;
    private final List<ModeChoiceCalibrationAnalysis.AnalysisResult> results = new ArrayList<>();

    @Inject
    public ModeChoiceCalibrationIterationListener(Scenario scenario, Config config,
                                                   ExperiencedPlansService experiencedPlans) {
        this.scenario = scenario;
        this.experiencedPlans = experiencedPlans;
        try {
            this.analysis = new ModeChoiceCalibrationAnalysis(
                    scenario, MunichMunicipalBoundary.loadDefault());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load the unchanged Munich boundary", exception);
        }
        this.writer = new ModeChoiceCalibrationAnalysisWriter(
                Path.of(config.controller().getOutputDirectory()));
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
        var plans = experiencedPlans.getExperiencedPlans();
        ValidateModeChoiceCalibrationConfig.require(
                plans.size() == scenario.getPopulation().getPersons().size(),
                "Experienced-plan map is incomplete at iteration " + event.getIteration());
        var result = analysis.analyze(event.getIteration(), plans);
        results.add(result);
        try {
            writer.write(results, event.isLastIteration());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write calibration analysis", exception);
        }
    }
}
