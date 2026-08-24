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
   maximum heap. It starts no QSim. It reads the completed run's experienced
   plans and fixed inputs, and writes only below
   `output/mode-choice-initial/analysis`.

Run step 05 before step 06. Step 06 records the actually experienced plans
and writes iteration-level modal-share, passenger-kilometre, trip-length and
distance-quality results after each mobsim, before replanning. After step 06
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
Output definitions and the blank empirical-target schema are documented in
[`docs/methodology/mode_choice_output_analysis.md`](../../docs/methodology/mode_choice_output_analysis.md).

For provenance, selection counts, conversion assumptions and methodological
limitations, see
[`docs/methodology/gtfs_2019_calibration_input.md`](../../docs/methodology/gtfs_2019_calibration_input.md).
