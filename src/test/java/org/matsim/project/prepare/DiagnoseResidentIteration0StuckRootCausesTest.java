package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiagnoseResidentIteration0StuckRootCausesTest {

    @Test
    void departureInFinalHourIsVeryLateWithoutAssumingRoutingFailure() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause.VERY_LATE_DEPARTURE,
                classify(47.5 * 3_600, "car", "car", false, false, false,
                        Double.NaN, false));
    }

    @Test
    void unfinishedCarLegRetainsNetworkClusterEvidence() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause
                        .CAR_NO_PROGRESS_OR_NETWORK_CLUSTER,
                classify(40 * 3_600, "car", "car", false, false, false,
                        47.9 * 3_600, false));
    }

    @Test
    void ptPassengerWhoWaitedButNeverBoardedIsExplicit() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause.PT_NEVER_BOARDED,
                classify(40 * 3_600, "pt", "transit_walk", true, false, false,
                        Double.NaN, false));
    }

    @Test
    void ptPassengerWhoBoardedButDidNotArriveIsExplicit() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause
                        .PT_BOARDED_NOT_ARRIVED,
                classify(40 * 3_600, "pt", "pt", true, true, true,
                        Double.NaN, false));
    }

    @Test
    void unfinishedTeleportedLegIsKeptSeparate() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause
                        .TELEPORTED_LEG_EXCEEDS_HORIZON,
                classify(40 * 3_600, "walk", "walk", false, false, false,
                        Double.NaN, false));
    }

    @Test
    void missingDepartureEvidenceFailsClosedAsInsufficient() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause
                        .INSUFFICIENT_EVIDENCE,
                classify(Double.NaN, "unknown", "unknown", false, false, false,
                        Double.NaN, false));
    }

    @Test
    void arrivalEvidencePreventsTeleportedExceedsHorizonClassification() {
        assertEquals(DiagnoseResidentIteration0StuckRootCauses.Cause
                        .INSUFFICIENT_EVIDENCE,
                classify(40 * 3_600, "bike", "bike", false, false, false,
                        Double.NaN, true));
    }

    private static DiagnoseResidentIteration0StuckRootCauses.Cause classify(
            double departure, String routingMode, String legMode, boolean waited,
            boolean boarded, boolean onVehicle, double movement, boolean teleportArrival) {
        return DiagnoseResidentIteration0StuckRootCauses.classify(
                new DiagnoseResidentIteration0StuckRootCauses.ClassificationEvidence(
                        departure, routingMode, legMode, waited, boarded, onVehicle,
                        movement, teleportArrival));
    }
}
