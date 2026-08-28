package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnoseLiteratureBasedScoringTripCountMismatchTest {

    @Test
    void calculatesPersonLevelDeficitsWithoutConflatingEvidence() {
        var stuck = new DiagnoseLiteratureBasedScoringTripCountMismatch.StuckInfo(
                List.of(172_800.0), List.of("pt"));
        var complete = new DiagnoseLiteratureBasedScoringTripCountMismatch.PlanInfo(
                1, true, true);
        var stuckDeficit = DiagnoseLiteratureBasedScoringTripCountMismatch.comparePerson(
                "stuck", 2, complete, stuck, true, true);
        assertEquals(1, stuckDeficit.missingTrips());
        assertTrue(stuckDeficit.outputPlanComplete());
        assertTrue(stuckDeficit.stuck());
        assertEquals(List.of(172_800.0), stuckDeficit.stuckTimes());
        assertEquals(List.of("pt"), stuckDeficit.stuckModes());

        var incomplete = DiagnoseLiteratureBasedScoringTripCountMismatch.comparePerson(
                "incomplete", 2,
                new DiagnoseLiteratureBasedScoringTripCountMismatch.PlanInfo(
                        1, true, false), null, true, true);
        assertEquals(1, incomplete.missingTrips());
        assertFalse(incomplete.outputPlanComplete());
        assertFalse(incomplete.stuck());

        var additional = DiagnoseLiteratureBasedScoringTripCountMismatch.comparePerson(
                "additional", 1,
                new DiagnoseLiteratureBasedScoringTripCountMismatch.PlanInfo(
                        2, true, true), null, true, true);
        assertEquals(-1, additional.missingTrips());
        assertEquals(DiagnoseLiteratureBasedScoringTripCountMismatch.Status
                        .EXPLAINED_BY_STUCK_EVENTS,
                DiagnoseLiteratureBasedScoringTripCountMismatch.determineStatus(
                        257, 0, 257, 0, 0));
        assertEquals(DiagnoseLiteratureBasedScoringTripCountMismatch.Status
                        .UNEXPLAINED_REVIEW_REQUIRED,
                DiagnoseLiteratureBasedScoringTripCountMismatch.determineStatus(
                        257, 1, 257, 0, 0));
        assertEquals(DiagnoseLiteratureBasedScoringTripCountMismatch.Status
                        .EXPLAINED_BY_INCOMPLETE_FINAL_PLANS,
                DiagnoseLiteratureBasedScoringTripCountMismatch.determineStatus(
                        257, 0, 100, 157, 257));
    }
}
