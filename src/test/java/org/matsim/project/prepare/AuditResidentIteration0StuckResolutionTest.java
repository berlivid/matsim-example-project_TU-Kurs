package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditResidentIteration0StuckResolutionTest {

    @Test
    void unchangedSourceLinkWithContinuationIsCongestionNotDataError() {
        var link = link("x", "a", "b", 600);
        assertEquals(AuditResidentIteration0StuckResolution.CarCause
                        .PLAUSIBLE_BUT_SEVERE_CONGESTION,
                AuditResidentIteration0StuckResolution.classifyCar(link, link,
                        true, true, true, true, 20));
    }

    @Test
    void changedEndpointsFailAsConfirmedTopologyError() {
        var calibration = link("x", "a", "wrong", 600);
        var source = link("x", "a", "b", 600);
        assertEquals(AuditResidentIteration0StuckResolution.CarCause
                        .CONFIRMED_TOPOLOGY_ERROR,
                AuditResidentIteration0StuckResolution.classifyCar(calibration, source,
                        true, true, false, false, 20));
    }

    @Test
    void changedCapacityFailsAsConfirmedCapacityDiscontinuity() {
        var calibration = link("x", "a", "b", 100);
        var source = link("x", "a", "b", 600);
        assertEquals(AuditResidentIteration0StuckResolution.CarCause
                        .CONFIRMED_IMPLAUSIBLE_CAPACITY_DISCONTINUITY,
                AuditResidentIteration0StuckResolution.classifyCar(calibration, source,
                        true, true, false, false, 20));
    }

    @Test
    void ptClassesUseScheduleAndActualPassEvidence() {
        assertEquals(AuditResidentIteration0StuckResolution.PtCause.NO_LATER_SERVICE,
                pt(false, false, 0, 0, 0));
        assertEquals(AuditResidentIteration0StuckResolution.PtCause
                        .NO_COMPATIBLE_CONNECTION,
                pt(false, false, 3, 0, 0));
        assertEquals(AuditResidentIteration0StuckResolution.PtCause
                        .COMPATIBLE_SERVICE_NOT_BOARDED,
                pt(false, true, 3, 2, 1));
        assertEquals(AuditResidentIteration0StuckResolution.PtCause
                        .TRANSFER_MISSED_AFTER_DELAY,
                pt(true, true, 0, 0, 0));
        assertEquals(AuditResidentIteration0StuckResolution.PtCause
                        .INSUFFICIENT_EVIDENCE,
                AuditResidentIteration0StuckResolution.classifyPt(false,
                        false, false, 0, 0, 0));
    }

    @Test
    void scheduledCompatibleButNoObservedPassRemainsInsufficient() {
        assertEquals(AuditResidentIteration0StuckResolution.PtCause
                        .INSUFFICIENT_EVIDENCE,
                pt(false, true, 2, 1, 0));
    }

    @Test
    void physicalWalkPtRequestsRemainPtRoutingCasesAndTotalsReconcile() {
        List<AuditResidentIteration0StuckResolution.PersistentCase> cases =
                new ArrayList<>();
        for (int i = 0; i < 18; i++) cases.add(ptCase("pt-" + i, "walk"));
        cases.add(carCase("car"));

        var rows = AuditResidentIteration0StuckResolution.reconcileModes(cases);
        assertEquals(19, rows.stream().mapToLong(
                AuditResidentIteration0StuckResolution.ModeRow::count).sum());
        assertEquals(18, rows.stream().filter(row -> "walk".equals(row.physicalMode())
                && "pt".equals(row.routingMode())).mapToLong(
                AuditResidentIteration0StuckResolution.ModeRow::count).sum());
    }

    @Test
    void quotedCsvFieldsAreParsedWithoutChangingContent() {
        assertEquals(List.of("person", "a,b", "quoted \"value\"", ""),
                AuditResidentIteration0StuckResolution.parseCsvLine(
                        "person,\"a,b\",\"quoted \"\"value\"\"\","));
    }

    private static AuditResidentIteration0StuckResolution.PtCause pt(
            boolean boarded, boolean compatibleEver, long later, long laterCompatible,
            long actualCompatible) {
        return AuditResidentIteration0StuckResolution.classifyPt(true, boarded,
                compatibleEver, later, laterCompatible, actualCompatible);
    }

    private static AuditResidentIteration0StuckResolution.LinkSnapshot link(
            String id, String from, String to, double capacity) {
        return new AuditResidentIteration0StuckResolution.LinkSnapshot(id, from, to,
                10, 13.888, capacity, 1, Set.of("car"), Map.of("origid", id));
    }

    private static AuditResidentIteration0StuckResolution.PersistentCase ptCase(
            String id, String physical) {
        return new AuditResidentIteration0StuckResolution.PersistentCase(id,
                "regional_background", physical, "pt", "PT_NEVER_BOARDED",
                1, 1, 2, 2, "", true, false, false, false,
                "s", "d", "", "", "");
    }

    private static AuditResidentIteration0StuckResolution.PersistentCase carCase(String id) {
        return new AuditResidentIteration0StuckResolution.PersistentCase(id,
                "regional_background", "car", "car",
                "CAR_NO_PROGRESS_OR_NETWORK_CLUSTER", 1, 1, 2, 2,
                "x", false, false, false, false, "", "", "", "", "");
    }
}
