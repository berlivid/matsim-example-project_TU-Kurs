# Munich synthetic-2019 calibration input

> **Current workflow status:** steps 01--03 remain the protected GTFS 2019
> preparation and validation chain. The initial, Open-Tour and `BOTH_INSIDE`
> calibration runs are technical preliminary experiments. The productive
> resident architecture now retains all 324,043 persons, assigns runtime-only
> labels and analyzes all 137,540 trips made by 68,770 classified Munich
> residents. No simulation was started while building this architecture. See the
> [resident calibration method](../../docs/methodology/resident_mode_choice_calibration.md),
> [legacy BOTH_INSIDE note](../../docs/methodology/legacy/both_inside_calibration_preliminary.md)
> and [legacy Open-Tour note](../../docs/methodology/legacy/open_tour_mode_choice_experiment.md).

## Analytical role and current status

This isolated scenario supplies the public-transport input for a later common
mode-choice calibration. It combines the original 5-% population and public
road network with a **synthetic 2019 reference supply extracted from the
combined forecast dataset**. It is not a historical MVV GTFS snapshot. The
service date 13 February 2026 is only a technical activation date and must not
be interpreted as the historical reference year.

The GTFS subset, MATSim transit inputs, reference checks, temporal checks and
representative SwissRailRaptor connections are structurally validated. The
resident Run-11 Controller/QSim execution completed with normal shutdown on the
Uni server. Run 11C established that all 8,764 apparent physical differences
are PT requests realized as walk-only routes with `routingMode=pt`, including
1,376 resident trips, and that no true choice change occurred. Corrected Run
11B accepts only that evidenced transformation and still fails on every other
physical or choice transition. No mode-choice strategy is active in the
separate GTFS input-validation configuration.

The completed stuck-event audit demonstrated no network, schedule, conversion
or other objective pipeline error. The productive resident config therefore
uses the already tested `48:00:00` QSim horizon without changing links or
inventing PT services. Its known iteration-zero limitation is 818 affected
Munich residents (1.1895% of residents; approximately 0.595% of resident main
trips), monitored by an all-trip primary result and a stuck-trip sensitivity.

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
The preliminary rounds retained `fromSpecifiedModesToSpecifiedModes`; the
resident-based productive calibration retains that stable behavior.

## Productive resident-based calibration architecture

The productive config is
`config_resident_mode_choice_calibration.xml`. It loads the unchanged regional
population and assigns `munich_resident` (68,770), `regional_background`
(147,655) and `unresolved_background` (107,618) only in memory. The unresolved
group consists of persons without an identifiable home and must not be called
confirmed commuters or non-residents.

Only `munich_resident` receives `SubtourModeChoice`; both background groups
retain their existing modes and receive only `ChangeExpBeta` plus `ReRoute`.
The primary analysis includes all 137,540 resident trips. Its secondary
territorial breakdown is 123,186 `BOTH_INSIDE`, 7,177 `ORIGIN_ONLY`, 7,177
`DESTINATION_ONLY`, zero `BOTH_OUTSIDE` and zero invalid-coordinate trips.

Use the new run configurations in this order:

1. **10 Validate 2019 Resident Mode Choice Configuration** performs read-only
   input-hash, config, cohort, strategy and target checks. It starts no QSim.
2. **11 Run 2019 Resident Mode Choice Iteration-0 Validation** is the protected
   server-only iteration-zero run. It reuses the productive pipeline and changes
   only run ID, output directory and `lastIteration=0` in memory. It then runs
   the dedicated output validator automatically.
3. **11B Validate Existing 2019 Resident Mode Choice Iteration-0 Output**
   performs only the same read-only output validation against the fixed,
   already existing Run-11 directory. It starts no controller or QSim.
4. **11C Diagnose Existing 2019 Resident Iteration-0 Main Modes** compares
   physical output leg modes with official MATSim routing/choice modes in the
   preserved output. It starts no controller or QSim and changes no plan.
5. **11D Validate 2019 Resident Iteration-0 48h Horizon Test** performs the
   read-only preflight for the isolated four-override horizon test.
6. **11E Run 2019 Resident Iteration-0 48h Horizon Test** is the server-only
   test and automatic 43h-versus-48h stuck-event comparison.
7. **11F Compare 43h and 48h Resident Iteration-0 Stuck Events** repeats only
   the read-only comparison if the 48-hour QSim output already exists.
8. **12 Run Initial 2019 Resident Mode Choice Calibration** is the productive
   controller entry point. The horizon decision is complete; run 10 must pass
   immediately before it is invoked.
9. **13 Analyze Initial 2019 Resident Mode Choice Output** starts no QSim and
   is used only after a completed protected run.

The productive protected output is `output/resident-mode-choice-initial`; the
iteration-zero protected output is
`output/resident-mode-choice-iteration-0-validation`. Neither may exist before
Run 11, and no tool deletes or overwrites either directory.
The config uses `failIfDirectoryExists`. Initial mode constants are all zero,
trip-share targets are 34/24/18/24, and exact secondary Pkm-share targets are
62.945329/26.687543/6.695437/3.671691 percent for car/PT/bike/walk. Step 3
compiled and unit-tested this architecture but created no output directory and
ran no controller, MATSim mobility simulation or QSim.

### Server sequence for iteration zero

1. Pull the reviewed Step-4 commit and confirm that the two protected output
   directories above do not exist.
2. Run **10** and require its read-only PASS result.
3. Run **11** manually in IntelliJ with `-Xms4g -Xmx16g` and project-root
   working directory. Do not change program arguments or the productive XML.
4. Require the final console status `ITERATION-0 VALIDATION PASS` or
   `ITERATION-0 VALIDATION PASS WITH REVIEW REQUIRED`. `FAIL` blocks Run 12.
5. Review the five validation files in the new output's `analysis/` directory.
   A nonzero stuck count is explicitly `REVIEW_REQUIRED`, with no invented
   acceptance threshold.
6. Keep the complete output as evidence. Do not run **12** until the technical
   result has been reviewed and accepted.

The existing server Run 11 completed Controller/QSim and normal shutdown; its
automatic post-validation then stopped on an overly positional output-config
comparison. Do not repeat the simulation. After pulling the comparison fix,
run **10** and then **11B**. MATSim may reorder parameter sets and writes the
explicit default `swissRailRaptor` config group installed at runtime. The
unchanged pre-run guard still permits exactly the three documented overrides.
The post-run guard now matches known parameter sets by semantic identity and
strictly compares all values, including the runtime SwissRailRaptor defaults;
missing, duplicate, unsupported or unexpected sets fail with detailed keys.
Only after that validation passes are the five intended `analysis/` reports
written.

Run 11C matched all persons and trips and showed that the 8,764 differences
(1,376 resident) are exclusively input PT to physical walk with choice PT. It
found zero choice changes, missing or inconsistent routing modes, and changed
trip structures. Preserve the output and do not repeat Run 11 or overwrite the
Run-11C reports. After pulling the validator correction, run **10** and then
**11B** with no arguments and `-Xms2g -Xmx8g`. Run 11B derives physical and
choice transition matrices directly from the existing plans, so no new QSim or
iteration-history columns are required. Physical modes remain the basis for
the empirical modal split and Pkm; choice-mode shares are diagnostic only. Run
12 remains blocked until Run 11B passes and any stuck-event review is complete.

The accepted Run-11 output contains 2,417 unique stuck persons, including
1,190 Munich residents; every event is in hour 43, where that historical QSim
ended.
The protected schedule itself ends earlier: latest departure `29:40:00`,
largest route offset `32:35:00`, latest vehicle arrival `42:30:00`, and derived
schedule horizon `43:00:00`. This points to boundary handling of still-active
agents rather than a truncated timetable, but causality is not assumed.

The separate Run-11E test changes only run ID, output directory,
`lastIteration=0` and QSim end time to `48:00:00` in memory. It writes to
`output/resident-mode-choice-iteration-0-horizon-48h` and never changes or
deletes the 43-hour output. Forty-eight hours is initially a technical horizon
test, not a calibrated parameter.

### Server sequence for the 48-hour horizon test

1. Preserve the complete 43-hour Run-11 output and pull the reviewed code.
2. Run **10** and require `PASS`.
3. Run **11D** and require its read-only pre-run `PASS`.
4. Confirm that `output/resident-mode-choice-iteration-0-horizon-48h` and the
   productive `output/resident-mode-choice-initial` do not exist.
5. Run **11E** with `-Xms4g -Xmx16g`, no arguments and project-root working
   directory. It runs QSim once and compares both outputs automatically.
6. If QSim completed but automatic comparison was interrupted, run **11F**
   with `-Xms2g -Xmx8g`; do not repeat 11E.
7. Review the comparison CSV, summary and report. Accept 48 hours for a later
   productive-config change only if all 2,417 old events disappear, no old or
   new person is stuck, no event occurs in hour 48, normal validation passes
   and all protected inputs remain unchanged. Any residual event is
   `REVIEW_REQUIRED` and keeps Run 12 blocked.

The completed 48-hour output remains technical evidence rather than an
accepted productive setting. A read-only root-cause diagnostic found 716
observed arrivals after 43:00 and 1,701 persistent cases, including 818 Munich
residents. The persistent resident cases are 552 car and 266 PT. Across all
cohorts, 897 car cases show unfinished network movement, 505 PT cases never
boarded, 291 PT cases boarded and later waited for a connection, and only 8
cases departed during the final hour. Links `419626`, `16208` and `453133`
contain the last movement of 867 car cases. Detailed generated evidence is under
`generated/resident_iteration0_stuck_root_cause/`. This result calls for a
targeted link/topology and late-transfer audit, not another horizon extension.
This was the intermediate diagnosis; the completed follow-up audit found no
objective correction to test. The approved productive treatment is documented
below.

Iteration zero tests execution, routing, transit, runtime cohorts and analysis;
it is not evidence of calibration or convergence. Choice modes must match the
input. The narrowly evidenced PT-to-walk physical routing transformation is
reported separately and is not an endogenous mode-choice change. Actual
resident mode changes are evaluated only in the later iterations 0--20 run.

## Productive 48-hour run and stuck-trip sensitivity

The protected productive config now differs from its previous version only in
`qsim.endTime=48:00:00`. Run 10 enforces that value together with all unchanged
inputs, seed, capacity factors, targets, strategies and mode constants. Run 12
continues to execute iterations 0--20 and writes only to
`output/resident-mode-choice-initial` with fail-if-exists protection.

Each iteration records resident stuck events, unique affected residents,
event-matched affected resident main trips, person/trip shares, routing-mode
distribution and differences from iteration 0. A resident stuck-trip share
above 1.0% is `REVIEW_REQUIRED`, not a reason to alter the simulation.

After normal completion, Run 13 reads the final plans and final-iteration
events without starting QSim. It reports (A) all resident selected-plan main
trips as the primary empirical-comparison scope and (B) a sensitivity excluding
only event-matched stuck main trips. Both report physical trip/Pkm shares, Pkm
totals, mean distances and target gaps. Differences greater than 0.5 percentage
points per mode or 1.0% in total Pkm are review flags, not universal MATSim
standards.

Server order: confirm that `output/resident-mode-choice-initial` is absent,
run 10, run 12 once, then run 13. No additional iteration-zero test is required
for this horizon adoption because no model input or behavioral setting was
corrected.

## Productive resident parameter Round 1

The completed initial 0--20 run ends at physical shares of 45.559837138% car,
14.688817798% PT, 29.740439145% bike and 10.010905918% walk. Its iteration-20
stuck sensitivity passes: 54 affected resident main trips (0.039261306%), a
maximum modal effect of 0.0336 percentage points and a total-Pkm effect of
0.0771%.

The versioned Round-1 specification applies the damped car-reference
log-share-ratio rule with factor 0.5. Constants are car 0.000000, PT 0.391817,
bike -0.104735 and walk 0.583522. The separate config
`config_resident_mode_choice_calibration_round_1.xml` changes only run ID,
output directory, last iteration (40) and those approved constants. It keeps
all protected inputs, cohorts, seed, 48-hour horizon, strategies, routing,
capacity and other scoring values unchanged.

Use **R1A** to validate, **R1B** to run once on the server, and **R1C** to
postprocess. R1C uses the shared physical/choice, resident, StuckEvent and
sensitivity code and evaluates exactly iterations 31--40. It applies the
study-specific review criteria of 0.10 pp/iteration trend, 1.0 pp range and
1.0% resident stuck trips. Round-1 output is protected at
`output/resident-mode-choice-round-1`; the initial output must not be removed,
renamed or overwritten.

## Productive resident parameter Round 2

Round 1 ended at 53.544423440% car, 15.002908245% PT, 27.834811691%
bike and 3.617856624% walk, with seven resident main trips affected by
StuckEvents in iteration 40. Round 2 applies the unchanged damped log-share
method cumulatively. Its constants are car 0.000000, PT 0.853797, bike
-0.095617 and walk 1.756684.

`config_resident_mode_choice_calibration_round_2.xml` again uses the original
population. Relative to Round 1 it changes only run ID, output directory,
`lastIteration=60`, and PT, bike and walk constants. The 48-hour horizon and
innovation-disable fraction 0.8 remain unchanged; the expected disable
iteration is 48 (innovation remains active through 48 and is disabled from
iteration 49). Output is protected at
`output/resident-mode-choice-round-2`.

Use **R2A** locally or on the server for read-only validation, **R2B** exactly
once on the server, and **R2C** only after normal completion. R2C evaluates
iterations 51--60 and reports `CONVERGED` separately from
`WITHIN_TARGET_TOLERANCE`. Overall `CALIBRATED` requires both statuses for all
four modes and a maximum late resident stuck-trip share no greater than 1.0%.
The shared reports continue to contain physical and choice-mode metrics,
normalized Pkm diagnostics and StuckEvent sensitivity.

## Productive resident parameter Round 3

Round 3 is derived from the Round-2 physical late means over iterations
51--60: 44.306529010% car, 40.490111967% PT, 11.347971499% bike and
3.855387524% walk. The unchanged damped car-reference log-ratio formula gives
constants car 0.000000000, PT 0.724680779, bike 0.267435138 and walk
2.803360913. The calculation is preserved in
`docs/calibration/resident_mode_choice_calibration_round_3.csv`.

`config_resident_mode_choice_calibration_round_3.xml` loads the same original
population and retains iterations 0--60, seed 4711, the 48-hour horizon,
strategy weights, innovation fraction, capacity, routing and all other scoring
parameters. Relative to Round 2, only run ID, protected output directory and
the PT, bike and walk constants may differ. Innovation remains active through
iteration 48, and evaluation again uses iterations 51--60. Output is protected
at `output/resident-mode-choice-round-3`.

Use **R3A** for read-only preflight, **R3B** exactly once on the server, and
**R3C** only after normal completion. R3C validates and summarizes the shared
analysis and adds a Round-2-versus-Round-3 comparison. Iteration 10 is not used
as calibration evidence because it is a transient point; stable late physical
resident shares determine convergence and target fit. The controlled walk
sensitivity test was omitted because of the available thesis timeframe. This
is a methodological limitation, not proof that the weak walk response is
explained, and it requires cautious interpretation of the Round-3 result.

## Isolated open-tour test

The separate five-iteration experiment tested
`betweenAllAndFewerConstraints`. Its cohort diagnostic was incomplete
(107,618 identifiers but zero reconstructed current trips), and 8,465
cumulative stuck events were observed. It cannot support a cohort mode or
chain-location conclusion, and the alternative is rejected because it relaxes
end-of-day location consistency for chain-based car and bike.

The Open-Tour config, ignored output and detailed historical documentation are
preserved. Its Java entry points, focused tests and IntelliJ configurations
08--10 have been removed from the active workflow. See the
[legacy decision record](../../docs/methodology/legacy/open_tour_mode_choice_experiment.md).

## Existing stuck-event audit

**30 Analyze Existing 2019 Calibration Stuck Events** starts no QSim. It scans
only event files that already exist under the initial and open-tour-test output
directories, avoids counting a root and iteration copy twice, reports missing
iterations explicitly, and writes only to the fail-closed ignored directory
`generated/mode_choice_stuck_event_audit/`. Remove or rename that generated
directory deliberately before a rerun; the tool never overwrites it. Counts by
iteration, mode, hour and unique person, plus frequent links, are descriptive
and do not establish a cause.

## Mode-choice calibration round 1

Round 1 used a `BOTH_INSIDE`, `ALL_PLANS` target scope and is not the final
thesis calibration. Its completed output passed the structural checks for
iterations 0--20, 324,043 persons, 540,468 main trips and 160,603
`BOTH_INSIDE` trips, with no unknown modes or invalid distances. It was
directionally useful but not converged. The ignored output, config, Java
implementation and versioned evidence remain unchanged; obsolete IntelliJ
configurations 12--14 have been removed.

## Mode-choice calibration round 2

Round 2 was prepared as a longer second `BOTH_INSIDE` experiment and does not
define the productive resident-based method. Its config, Java classes,
validators and tests remain historical reproducibility evidence. Obsolete
IntelliJ configurations 15--17 have been removed, and no existing output is
deleted. Round details and authoritative trip/pkm targets are preserved in the
[legacy preliminary-round note](../../docs/methodology/legacy/both_inside_calibration_preliminary.md).
