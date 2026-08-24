package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;

class ModeChoiceSelectedPlansAndStuckEventsTest {

    @Test
    void selectedPlanSnapshotIncludesOpenAndClosedPlansAndAllMainTrips() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var factory = scenario.getPopulation().getFactory();
        var open = factory.createPerson(Id.createPersonId("open"));
        open.addPlan(plan(false));
        scenario.getPopulation().addPerson(open);
        var closed = factory.createPerson(Id.createPersonId("closed"));
        closed.addPlan(plan(true));
        scenario.getPopulation().addPerson(closed);

        var snapshot = ModeChoiceCalibrationIterationListener.selectedPlanSnapshot(scenario);
        assertEquals(2, snapshot.size());
        assertEquals(2, org.matsim.core.router.TripStructureUtils.getTrips(snapshot.get(open.getId())).size());
        assertEquals(2, org.matsim.core.router.TripStructureUtils.getTrips(snapshot.get(closed.getId())).size());
        assertEquals(List.of("home", "work", "other"), snapshot.get(open.getId())
                .getPlanElements().stream().filter(element -> element instanceof org.matsim.api.core.v01.population.Activity)
                .map(element -> ((org.matsim.api.core.v01.population.Activity) element).getType()).toList());
    }

    @Test
    void stuckCountersResetPerIterationButRetainCumulativeCounts(@TempDir Path temp) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        scenario.getConfig().qsim().setEndTime(43 * 3600);
        scenario.getConfig().controller().setOutputDirectory(temp.toString());
        var listener = new ModeChoiceStuckEventListener(scenario, scenario.getConfig());
        listener.handleEvent(stuck(100, "p1", "car"));
        listener.handleEvent(stuck(200, "p1", "car"));
        listener.handleEvent(stuck(300, "p2", "pt"));
        assertEquals(3, listener.currentEventCount());
        assertEquals(3, listener.cumulativeEventCount());
        assertEquals(2, listener.cumulativeUniquePersonCount());
        listener.reset(1);
        assertEquals(0, listener.currentEventCount());
        assertEquals(3, listener.cumulativeEventCount());
        listener.handleEvent(stuck(400, "p2", "pt"));
        assertEquals(1, listener.currentEventCount());
        assertEquals(4, listener.cumulativeEventCount());
        assertEquals(2, listener.cumulativeUniquePersonCount());
    }

    @Test
    void stuckCsvIsSortedUniqueAndReportsEndWindow() {
        var record = new ModeChoiceStuckEventListener.StuckRecord(
                Id.createPersonId("p"), "car", "l", 42.5 * 3600);
        var zero = ModeChoiceStuckEventListener.snapshot(0, List.of(record), 1, 1, 100, 43 * 3600);
        var one = ModeChoiceStuckEventListener.snapshot(1, List.of(), 1, 1, 100, 43 * 3600);
        String csv = ModeChoiceStuckEventListener.csv(List.of(one, zero));
        assertTrue(csv.indexOf("\n0,") < csv.indexOf("\n1,"));
        assertTrue(csv.contains("FINAL_HOUR_BEFORE_OR_AT_QSIM_END"));
        assertTrue(csv.contains("1,all,NO_EVENTS,0,0"));
        assertThrows(IllegalStateException.class,
                () -> ModeChoiceStuckEventListener.csv(List.of(zero, zero)));
    }

    @Test
    void auditorPrefersIterationFileAndReportsMissingIterationsWithoutDuplication(
            @TempDir Path temp) throws Exception {
        Path output = temp.resolve("output");
        Path iteration = output.resolve("ITERS/it.1/run.1.events.xml");
        Files.createDirectories(iteration.getParent());
        writeEvents(iteration, 100, "p1");
        writeEvents(output.resolve("run.output_events.xml"), 200, "duplicate-root");
        Path target = temp.resolve("generated");
        AnalyzeModeChoiceStuckEvents.audit(List.of(
                new AnalyzeModeChoiceStuckEvents.OutputSpec("test", output, 1)), target);
        String summary = Files.readString(target.resolve("stuck_events_by_iteration.csv"));
        assertTrue(summary.contains("test,0,false,,0,0"));
        assertTrue(summary.contains("test,1,true"));
        assertTrue(summary.contains(",1,1,100.000,100.000"));
        assertFalse(summary.contains("duplicate-root"));
        assertThrows(IllegalStateException.class, () -> AnalyzeModeChoiceStuckEvents.audit(
                List.of(new AnalyzeModeChoiceStuckEvents.OutputSpec("test", output, 1)), target));
    }

    private static org.matsim.api.core.v01.population.Plan plan(boolean returnHome) {
        var factory = PopulationUtils.getFactory();
        var plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(4_468_000, 5_330_000)));
        plan.addLeg(factory.createLeg("walk"));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(4_469_000, 5_330_000)));
        plan.addLeg(factory.createLeg("walk"));
        plan.addActivity(factory.createActivityFromCoord(returnHome ? "home" : "other",
                returnHome ? new Coord(4_468_000, 5_330_000) : new Coord(4_470_000, 5_330_000)));
        return plan;
    }

    private static PersonStuckEvent stuck(double time, String person, String mode) {
        return new PersonStuckEvent(time, Id.createPersonId(person), Id.createLinkId("link"), mode);
    }

    private static void writeEvents(Path file, double time, String person) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<events version=\"1.0\">\n"
                + "<event time=\"" + time + "\" type=\"stuckAndAbort\" person=\""
                + person + "\" link=\"l\" legMode=\"car\" />\n</events>\n");
    }
}
