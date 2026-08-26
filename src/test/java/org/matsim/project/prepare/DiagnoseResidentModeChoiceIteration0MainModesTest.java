package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;

class DiagnoseResidentModeChoiceIteration0MainModesTest {
    private PopulationFactory factory;
    private MunichTripBoundaryFilter boundaryFilter;
    private Coord inside;

    @BeforeEach
    void setUp() throws Exception {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        factory = scenario.getPopulation().getFactory();
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        var coordinate = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(coordinate.x, coordinate.y);
        boundaryFilter = new MunichTripBoundaryFilter(boundary);
    }

    @Test
    void unchangedCarTripKeepsPhysicalAndChoiceModeSeparate() {
        var row = diagnose(oneLeg("car", null, "work"),
                oneLeg("car", "car", "work"));

        assertEquals("car", row.inputMode());
        assertEquals("car", row.physicalMode());
        assertEquals("car", row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .UNCHANGED_PHYSICAL_AND_CHOICE, row.status());
    }

    @Test
    void unchangedWalkTripIsUnchanged() {
        var row = diagnose(oneLeg("walk", null, "work"),
                oneLeg("walk", "walk", "work"));

        assertEquals("walk", row.physicalMode());
        assertEquals("walk", row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .UNCHANGED_PHYSICAL_AND_CHOICE, row.status());
    }

    @Test
    void routedPtTripWithTransitWalkAndPtLegsKeepsPtChoice() {
        var row = diagnose(oneLeg("pt", null, "work"), routedPt(false));

        assertEquals("pt", row.physicalMode());
        assertEquals("pt", row.choiceMode());
        assertEquals(List.of("transit_walk", "pt", "transit_walk"), row.legModes());
        assertEquals(List.of("pt", "pt", "pt"), row.routingModes());
        assertEquals(2, row.stageActivityTypes().size());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .UNCHANGED_PHYSICAL_AND_CHOICE, row.status());
    }

    @Test
    void walkOnlyRoutedPtRequestPreservesChoiceDespitePhysicalDifference() {
        var row = diagnose(oneLeg("pt", null, "work"),
                oneLeg("walk", "pt", "work"));

        assertEquals("walk", row.physicalMode());
        assertEquals("pt", row.choiceMode());
        assertNotEquals(row.physicalMode(), row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .PHYSICAL_CHANGED_CHOICE_PRESERVED, row.status());
    }

    @Test
    void genuinePtToWalkChoiceChangeRemainsVisible() {
        var row = diagnose(oneLeg("pt", null, "work"),
                oneLeg("walk", "walk", "work"));

        assertEquals("walk", row.physicalMode());
        assertEquals("walk", row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .CHOICE_MODE_CHANGED, row.status());
    }

    @Test
    void missingRoutingModeIsNotInferredFromPhysicalMode() {
        var row = diagnose(oneLeg("car", null, "work"),
                oneLeg("car", null, "work"));

        assertEquals("car", row.physicalMode());
        assertEquals("<missing>", row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .ROUTING_MODE_MISSING, row.status());
    }

    @Test
    void inconsistentRoutingModesAreReported() {
        Plan output = factory.createPlan();
        output.addActivity(activity("home", inside));
        output.addLeg(leg("transit_walk", "pt"));
        output.addActivity(activity(PtConstants.TRANSIT_ACTIVITY_TYPE, inside));
        output.addLeg(leg("walk", "walk"));
        output.addActivity(activity("work", inside));

        var row = diagnose(oneLeg("pt", null, "work"), output);
        assertEquals("<inconsistent>", row.choiceMode());
        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .ROUTING_MODE_INCONSISTENT, row.status());
    }

    @Test
    void changedMainActivityStructureIsExplicit() {
        var row = diagnose(oneLeg("car", null, "work"),
                oneLeg("car", "car", "shopping"));

        assertEquals(DiagnoseResidentModeChoiceIteration0MainModes.DiagnosticStatus
                .TRIP_STRUCTURE_CHANGED, row.status());
    }

    @Test
    void stageActivitiesDoNotCreateAdditionalMainTrips() {
        List<DiagnoseResidentModeChoiceIteration0MainModes.TripDiagnostic> rows =
                DiagnoseResidentModeChoiceIteration0MainModes.diagnosePlans(
                        "person", ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                        oneLeg("pt", null, "work"), routedPt(false), boundaryFilter);

        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().stageActivityTypes().size());
    }

    @Test
    void exampleSelectionIsBoundedAndDeterministic() {
        var examples = new DiagnoseResidentModeChoiceIteration0MainModes
                .BoundedExamples(200);
        List<DiagnoseResidentModeChoiceIteration0MainModes.TripDiagnostic> rows =
                new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            String person = String.format("person-%03d", index);
            rows.add(DiagnoseResidentModeChoiceIteration0MainModes.diagnosePlans(
                    person, ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                    oneLeg("pt", null, "work"), oneLeg("walk", "pt", "work"),
                    boundaryFilter).getFirst());
        }
        Collections.reverse(rows);
        rows.forEach(examples::add);

        assertEquals(200, examples.rows().size());
        assertEquals("person-000", examples.rows().getFirst().personId());
        assertEquals("person-199", examples.rows().getLast().personId());
    }

    private DiagnoseResidentModeChoiceIteration0MainModes.TripDiagnostic diagnose(
            Plan input, Plan output) {
        return DiagnoseResidentModeChoiceIteration0MainModes.diagnosePlans(
                "person", ResidentCalibrationSubpopulations.MUNICH_RESIDENT,
                input, output, boundaryFilter).getFirst();
    }

    private Plan oneLeg(String mode, String routingMode, String destinationType) {
        Plan plan = factory.createPlan();
        plan.addActivity(activity("home", inside));
        plan.addLeg(leg(mode, routingMode));
        plan.addActivity(activity(destinationType, inside));
        return plan;
    }

    private Plan routedPt(boolean walkOnly) {
        if (walkOnly) return oneLeg("walk", "pt", "work");
        Plan plan = factory.createPlan();
        plan.addActivity(activity("home", inside));
        plan.addLeg(leg("transit_walk", "pt"));
        plan.addActivity(activity(PtConstants.TRANSIT_ACTIVITY_TYPE, inside));
        plan.addLeg(leg("pt", "pt"));
        plan.addActivity(activity(PtConstants.TRANSIT_ACTIVITY_TYPE, inside));
        plan.addLeg(leg("transit_walk", "pt"));
        plan.addActivity(activity("work", inside));
        return plan;
    }

    private Leg leg(String mode, String routingMode) {
        Leg leg = factory.createLeg(mode);
        if (routingMode != null) leg.setRoutingMode(routingMode);
        return leg;
    }

    private Activity activity(String type, Coord coord) {
        return factory.createActivityFromCoord(type, coord);
    }
}
