package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.Map;

/** Server-only execution of the isolated 48-hour resident iteration-0 horizon test. */
public final class RunMatsim2019ResidentModeChoiceIteration0Horizon48h {
    private RunMatsim2019ResidentModeChoiceIteration0Horizon48h() { }

    public static void main(String[] args) throws Exception {
        try {
            ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                    "The 48-hour horizon-test runner accepts no arguments");
            Map<Path, String> protectedBefore =
                    ValidateResidentModeChoiceCalibrationConfig.captureProtectedInputHashes();
            var validation = ValidateResidentModeChoiceIteration0Horizon48hConfig
                    .validateForServerRun();
            ResidentModeChoiceCalibrationRunSupport.run(validation.config());
            var result = CompareResidentModeChoiceIteration0HorizonStuckEvents
                    .validateCompareAndWrite(protectedBefore);
            System.out.println(result.reviewRequired()
                    ? "RESIDENT ITERATION-0 HORIZON-48H TEST PASS WITH REVIEW REQUIRED"
                    : "RESIDENT ITERATION-0 HORIZON-48H TEST PASS");
        } catch (Throwable failure) {
            System.err.println("RESIDENT ITERATION-0 HORIZON-48H TEST FAIL");
            if (failure instanceof Exception exception) throw exception;
            throw failure;
        }
    }
}
