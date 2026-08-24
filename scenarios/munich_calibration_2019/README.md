# Munich synthetic-2019 calibration input

## Analytical role and current status

This isolated scenario supplies the public-transport input for a later common
mode-choice calibration. It combines the original 5-% population and public
road network with a **synthetic 2019 reference supply extracted from the
combined forecast dataset**. It is not a historical MVV GTFS snapshot. The
service date 13 February 2026 is only a technical activation date and must not
be interpreted as the historical reference year.

The GTFS subset, MATSim transit inputs, reference checks, temporal checks and
representative SwissRailRaptor connections are structurally validated. Full
end-to-end approval remains conditional on a normal iteration-zero shutdown on
the Uni server. No mode-choice strategy is active in the validation
configuration.

## Required server input

The unchanged source archive must be copied manually to:

`original-input-data/mvv_gtfs_2019/gtfs_2019.zip`

Its expected SHA-256 is
`92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4`.
The source ZIP is intentionally ignored by Git and is not transferred through
the repository. The derived GTFS ZIP and all three MATSim transit files are
also ignored; they must be rebuilt on the server from the versioned Java code
and specification.

## Point-and-click sequence on the Uni server

Open the project as a Maven project in IntelliJ and run these shared
configurations in order from the project root:

1. **01 Build Synthetic GTFS 2019** runs
   `org.matsim.project.prepare.BuildSyntheticGtfs2019Reference` with its
   implemented `build` argument. It validates the raw archive, applies the
   approved `Analyse_2019=1` and model-space rules, preserves complete selected
   trips, corrects route types and writes
   `original-input-data/mvv_gtfs_2019/synthetic_2019_reference.zip`.
2. **02 Create GTFS 2019 Calibration Transit** runs
   `org.matsim.project.prepare.CreateGtfs2019CalibrationTransit` with an 8 GB
   maximum heap. It creates and rereads `network-with-pt.xml.gz`,
   `transitSchedule.xml.gz` and `transitVehicles.xml.gz` under
   `scenarios/munich_calibration_2019/input_transit/`. It also audits all
   schedule times and writes the finite, schedule-derived QSim end time to the
   validation config.
3. **03 Validate GTFS 2019 Calibration Input** runs
   `org.matsim.project.prepare.ValidateGtfs2019CalibrationInput` with a 12 GB
   maximum heap. It first repeats structural and representative PT-routing
   checks, then loads the original 324,043-person 5-% population and executes
   only iteration 0 from `config_input_validation.xml`.

The final validation requires at least 8 GB Java heap; 12 GB is recommended
and is configured for step 3. Its output directory must not already exist
because the configuration deliberately uses `failIfDirectoryExists`.

## Recovery from the unbounded server run

The non-terminating server run used `qsim.endTime=undefined`. The similarly
named `hermes.endTime=30:00:00` did not control QSim. A read-only audit found
the last accepted vehicle arrival at `42:30:00`; the corrected generator sets
QSim end time to the first following complete hour, `43:00:00`. The validator
now fails before QSim if the end time is missing, non-finite, inconsistent with
the schedule, or if any accepted vehicle reaches it. Services reaching the
following service day are retained, subject to a fail-closed 48-hour maximum.

For the existing Uni-server checkout, use this exact recovery sequence:

1. pull the correction commit;
2. delete only
   `scenarios/munich_calibration_2019/output/input-validation-qsim2`, which is
   the incomplete output of the stopped run;
3. rerun **03 Validate GTFS 2019 Calibration Input**.

Do not rerun step 02 for this recovery: the GTFS subset and the three MATSim
transit inputs did not change. Step 02 will apply the same time policy during a
future clean transit rebuild. Step 01 is also unnecessary for this recovery.

## Local validation evidence and limitation

The local build, referential checks and representative bus, tram, subway and
rail routes passed. Focused tests also cover the explicit finite end time,
excessive-duration rejection and valid post-midnight service. No full local
QSim was started for this correction. The earlier local full-population attempt
was limited by a 3,936 MB Maven heap, while the later server attempt exposed
the independent undefined-end-time defect. Neither incomplete output may be
used for analysis.

At the original transfer stage, the input was only structurally ready and
required a successful server-side step 3. The corrected input subsequently
completed iteration 0 on the Uni server on 24 August 2026 with all 324,043
persons, process exit code 0 and
`GTFS 2019 END-TO-END VALIDATION PASS`. It is therefore technically available
for the separate mode-choice diagnostic described below; its synthetic-source
provenance limitations remain unchanged. BAU 2040, Fast Track 2040 and GTFS
2037 are independent of this workflow and must not be rebuilt by these
configurations.

## Initial mode-choice diagnostic

Three additional shared run configurations prepare and analyze the next server stage:

4. **05 Validate 2019 Mode Choice Configuration** runs
   `org.matsim.project.prepare.ValidateModeChoiceCalibrationConfig` with at
   most 4 GB heap. It reads and validates
   `config_mode_choice_calibration.xml`, all input references, four offered
   modes, three strategy weights, zero starting constants and output
   protection. It does not load a scenario or start QSim.
5. **06 Run Initial 2019 Mode Choice Calibration** runs
   `org.matsim.project.prepare.RunMatsim2019ModeChoiceCalibration` with 16 GB
   maximum heap. It repeats the validation, refuses an existing output
   directory, loads only the synthetic-2019 calibration config, installs
   SwissRailRaptor and executes iterations 0 through 20.
6. **07 Analyze Initial 2019 Mode Choice Run** runs
   `org.matsim.project.prepare.AnalyzeModeChoiceCalibrationOutput` with an 8 GB
   maximum heap. It starts no QSim. It reads the completed run's full final
   `output_plans` and fixed inputs, and writes only below
   `output/mode-choice-initial/analysis`. It never overwrites or invents the
   listener's iteration history; it refreshes final-state products only.

Run step 05 before step 06. Step 06 records the complete selected and routed
plans and writes iteration-level modal-share, passenger-kilometre, trip-length,
distance-quality and separate stuck-event results after each mobsim, before the
next iteration's replanning. These plan metrics are not claimed to be wholly
experienced when persons become stuck. After step 06
terminates successfully, run step 07 as an independent final-result
reproduction check. Do not create or reuse
`scenarios/munich_calibration_2019/output/mode-choice-initial` beforehand:
`failIfDirectoryExists` is an intentional safeguard. Step 06 is a technical
diagnostic, not an empirically calibrated run. It offers only `car`, `pt`,
`walk` and `bike`; the initial constants are all zero. `ride` remains excluded
from endogenous choice because no matched-driver model or compatible passenger
target exists. `considerCarAvailability=false` is provisional because the
population has no licence, ownership or availability attributes.

The regional population is not spatially filtered. The municipal-boundary
logic is applied only to later calibration and result analysis. Detailed
method and limitations are documented in
[`docs/methodology/mode_choice_calibration_configuration.md`](../../docs/methodology/mode_choice_calibration_configuration.md).
Output definitions and the versioned target/reference schema are documented in
[`docs/methodology/mode_choice_output_analysis.md`](../../docs/methodology/mode_choice_output_analysis.md).

The copied first-run analysis contains only iteration 20. The earlier Run 07
implementation overwrote the listener history with its single final result;
no file in the copied folder can reconstruct iterations 0-19. Do not claim
convergence from that run. The corrected pipeline preserves future histories.
The alternative `betweenAllAndFewerConstraints` was tested but is not adopted.
The production calibration retains `fromSpecifiedModesToSpecifiedModes`.

## Isolated open-tour test

The alternative behavior is prepared in a separate config and output path. It
does not change `config_mode_choice_calibration.xml`. Run these shared IntelliJ
configurations in order on the server:

8. **08 Validate 2019 Open Tour Mode Choice Test** checks that the test config
   has exactly the approved four differences and that its output path does not
   exist. It starts no QSim.
9. **09 Run 2019 Open Tour Mode Choice Test** is the only step that starts the
   five-iteration test. It uses 16 GB maximum heap, refuses an existing output
   directory and never deletes output.
10. **10 Validate 2019 Open Tour Mode Choice Test Output** checks regular
    completion, histories 0 through 5, the iteration-5 final summary, the
    originally open cohort, mode changes, invalid distances, stuck events and
    car/bike resource locations. It is read-only and starts no QSim.

Do not run step 09 if step 08 fails. Do not create
`output/mode-choice-open-tour-test` manually. After step 09, run step 10 rather
than the general step 07 postprocessor. The output validator treats car or bike
ending away from the first activity as a separately reported implication of an
open day, while an intervening resource jump remains a blocking inconsistency.
The exact method and decision criteria are documented in
[`docs/methodology/mode_choice_open_tour_test.md`](../../docs/methodology/mode_choice_open_tour_test.md).

This test retained zero constants and was neither a new calibration round nor a
scenario-effect comparison. Its former `ExperiencedPlansService` cohort
diagnostic was incomplete (107,618 identifiers, zero current trips), while
8,465 cumulative stuck events were observed. It cannot support a cohort mode
or chain-location conclusion. Steps 08--10 are retained only as experimental
provenance and are no longer part of the production workflow.

## Existing stuck-event audit

**11 Analyze Existing 2019 Calibration Stuck Events** starts no QSim. It scans
only event files that already exist under the initial and open-tour-test output
directories, avoids counting a root and iteration copy twice, reports missing
iterations explicitly, and writes only to the fail-closed ignored directory
`generated/mode_choice_stuck_event_audit/`. Remove or rename that generated
directory deliberately before a rerun; the tool never overwrites it. Counts by
iteration, mode, hour and unique person, plus frequent links, are descriptive
and do not establish a cause.

For provenance, selection counts, conversion assumptions and methodological
limitations, see
[`docs/methodology/gtfs_2019_calibration_input.md`](../../docs/methodology/gtfs_2019_calibration_input.md).
