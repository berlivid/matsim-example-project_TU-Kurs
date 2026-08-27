# Resident-based 2019 mode-choice calibration

## Status and purpose

This document defines the productive calibration architecture prepared in Step
3 and its protected iteration-zero validation prepared in Step 4. Both are
locally compiled and structurally validated. Step 4 did not start a controller,
MATSim mobility simulation or QSim; the iteration-zero execution was reserved
for a deliberate manual server run before iterations 0--20 are authorized.
That Run-11 server execution has since completed Controller/QSim and normal
shutdown. Run 11C then established that all 8,764 apparent physical main-mode
differences are PT requests realized as walk-only routes while retaining
`routingMode=pt`; there were no true choice changes, missing or inconsistent
routing modes, or changed main-trip structures. Run 11B now applies this
evidence narrowly. The subsequent stuck-event audit found no objective input
or pipeline error. The documented methodological decision now accepts the
tested 48-hour horizon with an explicit all-trip primary result and a
stuck-affected-trip sensitivity; this preparation does not itself run Run 12.

The calibration estimates a common behavioral baseline for later BAU and Fast
Track comparisons. It does not filter the regional demand and does not modify
the source population. The complete 324,043-person five-percent population is
simulated so that road congestion, public-transport demand and travel
conditions continue to reflect regional traffic.

## Residence cohort and runtime labels

`MunichResidentClassifier` identifies residence from exact `home` main
activities in the selected plan and the EPSG:31468 municipal boundary. JTS
`covers` includes boundary points. Stage activities never establish residence,
and no trip origin or destination is used as a proxy for home.

After the unchanged scenario has been loaded, the runner assigns one of three
MATSim subpopulation attributes in memory:

| Runtime subpopulation | Persons | Interpretation |
|---|---:|---|
| `munich_resident` | 68,770 | Classified Munich residents; calibration cohort |
| `regional_background` | 147,655 | Classified non-residents; regional background traffic |
| `unresolved_background` | 107,618 | Persons without an identifiable home; unresolved background traffic |

The unresolved group must not be described as confirmed commuters or
non-residents. Its members remain in the simulation, but their residence cannot
be inferred defensibly. Runtime labels may later appear in output plans, yet
the source population remains byte-identical and is never rewritten.

## Replanning permissions

Replanning is explicitly scoped; no default or unscoped strategy is present.

| Subpopulation | ChangeExpBeta | ReRoute | SubtourModeChoice |
|---|---:|---:|---:|
| `munich_resident` | 0.8 | 0.1 | 0.1 |
| `regional_background` | 0.9 | 0.1 | none |
| `unresolved_background` | 0.9 | 0.1 | none |

Weights sum to 1.0 within every group. Background persons may select among
their existing plans and may re-route, but no configured strategy can change
their mode. Only `munich_resident` receives `SubtourModeChoice`.

The resident strategy offers `car`, `pt`, `walk` and `bike`, treats car and
bike as chain-based, and uses the stable
`fromSpecifiedModesToSpecifiedModes` behavior with
`considerCarAvailability=false`. All 68,770 residents have a closed subtour
and are technically eligible. The rejected
`betweenAllAndFewerConstraints` Open-Tour behavior and random single-trip mode
changes remain absent.

## Productive configuration and safeguards

The only productive resident calibration config is
`scenarios/munich_calibration_2019/config_resident_mode_choice_calibration.xml`.
It retains the validated synthetic-2019 network, unchanged five-percent
population, schedule, vehicles, 48-hour horizon, 0.05 flow/storage capacity
factors, SwissRailRaptor and seed 4711. It covers iterations 0--20 and starts
with car, PT, walk and bike constants at 0.0.

The protected run ID is `munich-calibration-2019-resident-initial`; output is
`scenarios/munich_calibration_2019/output/resident-mode-choice-initial`.
`failIfDirectoryExists` is mandatory. The validator and runner both refuse an
existing output, and no project component deletes or overwrites that directory.

The runner accepts no alternative config. It validates protected input hashes
and the complete cohort before loading the scenario, assigns and revalidates
runtime labels after loading, installs SwissRailRaptor and the resident
analysis listeners, and starts the controller only when its main class is
explicitly invoked.

The municipal-boundary protection uses canonical UTF-8/LF SHA-256
`EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26`.
Only CRLF or lone CR line endings in this Git text file are normalized to LF;
all other characters, whitespace, geometry declarations and coordinate values
remain hash-sensitive. This makes Linux and Windows checkouts reproducible
without weakening geometry protection. Population, network, schedule,
vehicles and all binary or compressed protected inputs retain raw-byte hashes.

## Protected iteration-zero validation

Run 11 is a thin server-side use of the productive pipeline, not a second
calibration method. It first performs the complete Run-10 validation against
`config_resident_mode_choice_calibration.xml`. It then derives an in-memory
configuration with exactly three differences:

| Controller value | Iteration-zero value |
|---|---|
| `runId` | `munich-calibration-2019-resident-iteration-0-validation` |
| `outputDirectory` | `scenarios/munich_calibration_2019/output/resident-mode-choice-iteration-0-validation` |
| `lastIteration` | `0` |

A positional snapshot comparison of every config module, parameter and
parameter set fails unless these are the only pre-run differences. This exact
guard is applied before MATSim can serialize or normalize the configuration
and remains unchanged. No second permanent MATSim config is written. Both
validation and productive output paths must be absent for a fresh Run 11. The
productive scenario loader, runtime cohort assignment, structural resident-trip
checks, SwissRailRaptor module, iteration analyzer and stuck-event listener are
then reused without an independent copy.

After a normal controller return, the output validator reads iteration 0
events, final plans and output config. It checks the expected MATSim network,
transit and iteration artifacts, exact population/cohort/trip counts, analysis
rows and background exclusion. Car departures and vehicle-traffic events must
exist; PT departures, transit-driver starts and passenger boardings must exist.
The controller log must record completed normal shutdown and contain no fatal
or error marker. Protected input hashes are compared before and after the run,
and the productive output path must still be absent.

MATSim does not promise that its output config preserves XML parameter-set
order or every implicit representation from the input config. In particular,
the approved SwissRailRaptor controller module installs its MATSim-2025.0
default config group before the output config is written. The post-run check
therefore compares the loaded configs semantically: strategy settings are
matched by subpopulation and strategy name; scoring modes by mode; activities
by activity type; teleported routing parameters by mode; and scoring parameter
groups by subpopulation. Path separators are normalized only for path-valued
parameters. Every matched value remains strict, the SwissRailRaptor fields
must equal the dependency's defaults, and missing, duplicated, unsupported or
additional parameter sets fail with exact expected and actual keys. Thus this
post-run comparison is order-independent but remains fail-closed for inputs,
seed, threads, capacity, horizon, scoring, routing, transit and strategy scope.

Input and output selected plans are compared by person and runtime cohort with
MATSim's stage-activity handling. The validator separately derives realized
physical mode from actual legs and choice mode from MATSim's official leg
`routingMode`. It requires zero true choice changes, missing or inconsistent
routing modes, and changed main-trip structures. The only accepted physical
transition is input PT to physical walk with output choice PT and diagnostic
status `PHYSICAL_CHANGED_CHOICE_PRESERVED`. Any other physical transition fails
closed. The count is observed and reported rather than used as a universal
threshold; the preserved output is additionally checked for its evidenced
8,764 overall and 1,376 resident cases.

### Preserved-output main-mode diagnostic

Run 11C reads only the authoritative input population, the preserved Run-11
final plans and the unchanged municipal boundary. It neither starts nor alters
MATSim and creates only four new diagnostic files in the existing `analysis/`
directory. It fails before writing if persons, per-person main-trip counts,
runtime cohorts or the 137,540 resident trips cannot be matched exactly.

The diagnostic deliberately keeps two mode concepts separate. The physical or
analysis main mode is obtained from the actual output leg modes with MATSim's
`DefaultAnalysisMainModeIdentifier`; this is also the definition currently used
by the calibration analyzer and iteration-zero validator. The choice mode is
obtained independently from each leg's official MATSim `routingMode`. Thus an
input PT trip routed as walk-only legs with `routingMode=pt` is reported as
`PHYSICAL_CHANGED_CHOICE_PRESERVED`, while a PT trip with `routingMode=walk`
remains a visible `CHOICE_MODE_CHANGED`. Missing, inconsistent and structurally
changed cases receive separate fail-closed diagnostic statuses.

Run 11C found 324,043 matched persons, 540,468 matched main trips and 137,540
resident main trips. All 8,764 physical differences (1,376 among residents)
are exactly input PT to physical walk with choice PT. It found zero true choice
changes, zero missing or inconsistent routing modes and zero changed trip
structures. The reports remain preserved as an independent diagnostic tool.
This evidence supports a narrow validator correction; it does not redefine a
mode and does not require QSim to be repeated.

Stuck events are descriptive rather than assigned an arbitrary threshold. They
are counted by runtime cohort, current routing main mode and hour, including
unique-person and population shares. Zero events yields `PASS`; a consistent
nonzero count yields `PASS WITH REVIEW REQUIRED`. Missing or unreadable events,
inconsistent counts or abnormal controller termination fail.

### Isolated 48-hour QSim-horizon test

The validated 43-hour iteration-zero output contains 2,417 unique stuck
persons, including 1,190 Munich residents, and every event is grouped in hour
43. At the time of that isolated test, the productive XML recorded
`qsim.endTime=43:00:00`. A fresh streaming
audit of the protected transit schedule confirms a latest departure at
`29:40:00`, a largest stop offset of `32:35:00`, a latest vehicle arrival at
`42:30:00`, and the existing schedule-derived horizon of `43:00:00`. Therefore
the timetable itself is not truncated, but agents or vehicles still active at
the QSim boundary may be converted to stuck events. The causal interpretation
must be tested rather than assumed.

The 48-hour test was a separate technical iteration-zero run. It derived an
in-memory config from the then-unchanged productive 43-hour XML and permitted exactly
four differences: run ID, output directory, `lastIteration=0`, and
`qsim.endTime=48:00:00`. It reuses the productive scenario loader, runtime
cohort assignment, strategies, SwissRailRaptor, analysis listeners, threads,
seed and every protected input. Its fail-closed output is
`output/resident-mode-choice-iteration-0-horizon-48h`; neither the productive
XML nor the preserved 43-hour output is changed.

After normal shutdown, the comparison validates the 48-hour output config,
normal log termination, exact runtime cohort assignments, readable events and
unchanged protected hashes. It groups both event files by runtime cohort, leg
mode and exact event time and reports whether counts disappear, decline,
persist, or move into hour 48. The comparison writes only to the new 48-hour
output:

- `analysis/iteration_0_horizon_43h_vs_48h_stuck_events.csv`
- `analysis/iteration_0_horizon_43h_vs_48h_summary.csv`
- `analysis/iteration_0_horizon_43h_vs_48h_report.md`

Forty-eight hours is not a calibrated behavioral parameter. It may be proposed
for the later productive calibration only if all 2,417 old cutoff events
disappear, no old affected person remains stuck, no new event occurs, no event
moves to hour 48, the output passes all technical checks, and protected inputs
remain unchanged. A decline with any residual event is `REVIEW_REQUIRED` and
does not authorize Run 12.

### Read-only diagnosis of the residual 48-hour cases

The completed 48-hour test reduced the affected population from 2,417 to
1,701 persons. All 716 persons removed from the stuck set have an observed
arrival after 43:00 in the longer event stream. The residual set comprises 818
Munich residents, 687 regional-background persons and 196 unresolved-background
persons. Its event-supported classification is:

| Technical evidence class | All persons | Munich residents |
|---|---:|---:|
| `VERY_LATE_DEPARTURE` | 8 | 6 |
| `CAR_NO_PROGRESS_OR_NETWORK_CLUSTER` | 897 | 546 |
| `PT_NEVER_BOARDED` | 505 | 212 |
| `PT_BOARDED_NOT_ARRIVED` | 291 | 54 |
| `TELEPORTED_LEG_EXCEEDS_HORIZON` | 0 | 0 |
| `INSUFFICIENT_EVIDENCE` | 0 | 0 |

The diagnostic streams each preserved event file once and retains only the
2,417 previously affected persons. It deterministically matches their event
departures to complete selected-plan legs while excluding stage activities as
main-trip boundaries. Person counts, runtime cohort counts, main-trip counts
and the expected 716/1,701 split fail closed. Existing output and scenario
inputs are read only; the five diagnostic products are written under
`generated/resident_iteration0_stuck_root_cause/`.

The evidence does not support treating the residual cases as a simple
48-hour-cutoff problem. Of the 897 car cases, 867 have their last vehicle
movement on links `419626` (403 persons), `16208` (317) or `453133` (147), and
every car record ends with an `entered link` movement. Of the 505 passengers
who never board, 487 have an explicit waiting-at-stop event. All 291 passengers
who boarded have subsequently left a vehicle and are waiting for a connection;
none is still aboard at the cutoff. Stops and last-used routes are dispersed:
the largest never-boarded stop clusters contain four persons each, and the
largest last-used route cluster contains eight persons.

Accordingly, extending the horizon to 48 hours is not accepted for productive
Run 12 on its own. The subsequent audit below tests the dominant links and PT
service directly rather than assuming that their persistence is an input
error.

### Fail-closed road and PT service audit

The resolution audit reuses the 1,701 generated person rows. It reads the
synthetic calibration network, its versioned road source and the actual 2019
schedule, then streams only the 48-hour events once. The 43-hour events are not
read again. Link occupancy includes both `LinkEnter` and initial
`VehicleEntersTraffic` entries and both `LinkLeave` and final
`VehicleLeavesTraffic` exits. Actual PT passage requires an observed
`VehicleDepartsAtFacility` event whose schedule route reaches the required
destination after the waiting stop.

| Link | Entries | Exits | Vehicles remaining | All persistent persons | Car-root-cause persons | Audit result |
|---|---:|---:|---:|---:|---:|---|
| `16208` | 1,575 | 1,257 | 318 | 318 | 317 | plausible but severe congestion |
| `419626` | 1,673 | 1,266 | 407 | 407 | 403 | plausible but severe congestion |
| `453133` | 1,395 | 1,248 | 147 | 147 | 147 | plausible but severe congestion |

The five-person difference between 872 persistent persons on these links and
the previously reported 867 car-root-cause cases consists of separately
classified very-late departures. All three links, every audited adjacent car
link and their source attributes are semantically identical to
`studyNetworkDense.xml`. Each target has an outgoing car continuation. None is
a dead end, none has an immediate downstream capacity reduction, and the
synthetic PT build introduced no endpoint, capacity, lane, mode or `origid`
change. The audit therefore does not prove a topology or capacity pipeline
error. Congestion is severe, but congestion alone is not permission to change
a link.

The 796 PT-routing cases divide as follows:

| Service evidence | All PT cases | Munich residents | Share of residents |
|---|---:|---:|---:|
| no later service | 250 | 72 | 0.1047% |
| no compatible connection | 180 | 108 | 0.1570% |
| compatible vehicle passed without boarding | 83 | 38 | 0.0553% |
| transfer missed after delay | 265 | 48 | 0.0698% |
| insufficient stop evidence | 18 | 0 | 0.0000% |

The 18 insufficient cases are exactly the formerly reported physical walk
events: 17 regional-background and one unresolved-background person. Their
`PersonStuck` leg is physically represented as walk, while the computational
routing and choice mode remains PT. They are part of the 796 PT cases, not an
additional category and not endogenous PT-to-walk choices.

Across PT cases, planned departure times range from 517 to 86,820 seconds
(median 39,897); final waiting starts range from 18,361 to 157,333 seconds
(median 70,026). Waiting until the 48-hour cutoff ranges from 15,467 to 154,439
seconds (median 102,765). The schedule audit does not invent replacement
services. A compatible passing vehicle without boarding may reflect vehicle
capacity or another operational boarding constraint; without that evidence it
is not promoted to a confirmed pipeline error.

No objective road-network or synthetic-schedule pipeline error was proved, so
no model correction is implemented. The 818 affected residents equal 1.1895%
of the resident cohort: 552 car-routing cases (0.8027%) and 266 PT-routing
cases (0.3868%). Scientifically defensible options are either to approve an
explicit sensitivity and reporting rule for this residual execution loss, or
to specify and test a separately justified congestion, late-service or
boarding-capacity assumption. Global capacity changes, a further horizon
extension and invented departures are excluded. Another iteration-zero run is
needed only after an approved correction; repeating the unchanged setup adds
no evidence. The methodological decision has since accepted the tested
48-hour horizon without changing links or transit service. The residual is
treated as a reported limitation and sensitivity, not as evidence for a model
input correction.

The automatic validator writes under the validation output's `analysis/`:

- `iteration_0_validation_summary.csv`
- `iteration_0_validation_report.md`
- `iteration_0_stuck_summary.csv`
- `iteration_0_mode_comparison.csv`
- `protected_input_hashes.csv`

Iteration 0 validates technical execution, routing, transit, cohort assignment
and analysis. It does not demonstrate calibrated mode choice or convergence.
Strategy scoping is proven structurally by config validation and focused tests;
actual resident mode changes are assessed only in the later 0--20 run.

## Primary and secondary analysis

The primary cohort comprises all 137,540 main trips made by
`munich_resident` persons. A boundary-crossing trip remains part of the
calibration because the analytical unit is the resident, not the trip's
territorial location. Current fixed spatial counts are:

| Secondary spatial category | Resident trips |
|---|---:|
| `BOTH_INSIDE` | 123,186 |
| `ORIGIN_ONLY` | 7,177 |
| `DESTINATION_ONLY` | 7,177 |
| `BOTH_OUTSIDE` | 0 |
| `INVALID_OR_MISSING_COORDINATE` | 0 |

The five categories must sum to 137,540 in every iteration. `BOTH_INSIDE`
remains a secondary territorial indicator and is no longer the primary
calibration definition. Trips of both background groups are excluded from all
target metrics even when they occur inside Munich.

The iteration listener uses every resident's complete selected scenario plan
at `AfterMobsim`, after routing/mobsim and before the next replanning step. It
does not use the incomplete former `ExperiencedPlansService` representation.
Per iteration it records physical resident trip counts and shares, physical
route-distance passenger-kilometres and shares, target differences, the five
spatial categories, resident stuck events by routing mode, and late-iteration
means, ranges and linear trends. For each iteration, the event listener also
reports unique affected residents, deterministically matched affected resident
main trips, their shares of resident persons and trips, and differences from
the observed iteration-zero values. These realized physical metrics alone are compared
with the MiD/Schröder targets. Additional non-target diagnostics record resident
choice/routing-mode counts and shares, physical-versus-choice transitions, and
PT requests resulting in walk-only physical routes. Selected-plan metrics are
planned/routed outcomes; stuck events remain a separate execution diagnostic.

The final Run-13 analysis writes two explicitly labelled variants. Variant A,
the thesis primary result, retains every selected-plan main trip of every
Munich resident, including a trip affected by `PersonStuckEvent`, so its demand
denominator remains comparable with the empirical mobility survey. Variant B
excludes only the individual main trips matched to a stuck event in the final
iteration; it neither drops whole persons nor changes any plan. Both variants
report physical trip split, physical Pkm split, raw daily sample Pkm, the
five-percent annualised diagnostic, mean trip distance and target gaps.

The review criteria are thesis-specific transparency rules: affected resident
main trips no greater than 1.0%, modal-share sensitivity no greater than 0.5
percentage points for each mode, and total-Pkm sensitivity no greater than
1.0%. Exceeding a criterion yields `REVIEW_REQUIRED`; it never changes a
constant, strategy, network capacity or service. The iteration-zero reference
is 818 affected residents (1.1895% of the resident cohort), corresponding to
approximately 0.595% of 137,540 resident main trips.

## Calibration targets

Schröder's four-mode trip shares are the primary targets:

| Mode | Trip-share target |
|---|---:|
| car | 34.0% |
| PT | 24.0% |
| bike | 18.0% |
| walk | 24.0% |

Annual passenger-kilometres and their exact normalized shares are secondary:

| Mode | Million Pkm/year | Exact normalized share |
|---|---:|---:|
| car | 10,637.49 | 62.945329% |
| PT | 4,510.08 | 26.687543% |
| bike | 1,131.50 | 6.695437% |
| walk | 620.50 | 3.671691% |

The normalized shares sum to exactly 100% at the documented precision. The
rounded 63/27/7/4 values sum to 101% and are not calibration targets.

Mode constants are not adjusted automatically. The writer reports raw
route-distance passenger-kilometres for the simulated five-percent sample day.
It also reports a transparent annualized diagnostic (`daily sample Pkm × 20 ×
365 / 1,000,000`). This is not an automatic calibration objective: the model
population universe does not reproduce the complete observed population total,
so scaled absolute Pkm have a population-total comparability limitation.

## Productive resident calibration Round 1

The completed initial run contains all iterations 0--20 and preserves 68,770
resident persons and 137,540 resident main trips in every iteration. Its final
physical trip shares are 45.559837138% car, 14.688817798% PT, 29.740439145%
bike and 10.010905918% walk. The final stuck-event sensitivity excludes 54
affected resident trips (0.039261306%); its maximum modal-share effect is
0.0336 percentage points and its total-Pkm effect is 0.0771%. It therefore
passes the documented sensitivity criteria.

Round 1 changes only alternative-specific constants, using car as the fixed
reference at zero. For each non-reference mode (m), the update is:

`0.5 * [ln(target_m/current_m) - ln(target_car/current_car)]`

The factor 0.5 is a conservative, thesis-specific damping choice intended to
reduce overshooting. The resulting constants are car 0.000000, PT 0.391817,
bike -0.104735 and walk 0.583522. Their inputs, undamped values, provenance and
rounding are versioned in
`docs/calibration/resident_mode_choice_calibration_round_1.csv` and checked by
the pre-run validator. No travel-time, distance, money, activity, strategy,
routing, capacity or availability parameter is adjusted.

`config_resident_mode_choice_calibration_round_1.xml` is a separate protected
config. Relative to the productive initial config, its only semantic
differences are run ID, output directory, `lastIteration=40`, and the three
nonzero approved constant changes; the fourth approved car constant remains
0.0. The population, network, transit files, runtime cohorts, seed, 48-hour
horizon, strategies and all other scoring values are identical. Output is
protected at `output/resident-mode-choice-round-1`.

Late evaluation uses exactly iterations 31--40. For every mode, the report
contains late mean, minimum, maximum, range, least-squares trend, final trip
share and trip-target difference, plus final normalized Pkm share and its
secondary target difference. The thesis-specific convergence review requires
an absolute trip-share trend no greater than 0.10 percentage points per
iteration, a range no greater than 1.0 percentage point, and no resident
stuck-trip share above 1.0%. Violations produce `REVIEW_REQUIRED`; they never
trigger an automatic parameter change. Absolute annual Pkm anchoring for the
external-cost calculation is a later and separate methodological step.

## Productive resident calibration Round 2

Round 1 completed with 68,770 resident persons and 137,540 resident main trips
in every iteration from 0 through 40. Its final physical shares were
53.544423440% car, 15.002908245% PT, 27.834811691% bike and 3.617856624% walk.
Seven resident main trips were affected by StuckEvents in iteration 40. The
complete Round-1 output remains protected and is used only as calibration
evidence; Round 2 again loads the unchanged original input population rather
than Round-1 output plans.

The same damped car-reference log-share-ratio rule produces cumulative Round-2
constants of car 0.000000, PT 0.853797, bike -0.095617 and walk 1.756684. The
Round-1 constants, final physical shares, targets, undamped and damped updates,
and cumulative values are versioned in
`docs/calibration/resident_mode_choice_calibration_round_2.csv`. Absolute Pkm
remain outside this parameter adjustment.

`config_resident_mode_choice_calibration_round_2.xml` differs from the Round-1
config only in run ID, protected output directory, `lastIteration=60`, and the
PT, bike and walk constants. Car remains the zero reference. The original
population path, seed, 48-hour horizon, strategies, routing, capacity factors,
cohorts and every other scoring value are unchanged. The innovation-disable
fraction remains 0.8, giving the expected disable iteration 48 in the 0--60
run. In MATSim 2025.0 terminology innovation switches off *after* iteration
48, so innovative-strategy weights become zero from iteration 49.

Late evaluation uses exactly iterations 51--60. Stability and target fit are
reported separately. `CONVERGED` requires an absolute physical trip-share
trend no greater than 0.10 percentage points per iteration and a late range no
greater than 1.0 percentage point. `WITHIN_TARGET_TOLERANCE` requires the final
physical trip-share difference to be within 1.0 percentage point. A mode that
is stable but far from its target is therefore never labelled simply as a
pass. Overall status is `CALIBRATED` only if all four modes satisfy both rules
and the maximum late resident stuck-trip share is no greater than 1.0%.
Normalized physical Pkm shares remain secondary; physical-versus-choice
transitions, choice-mode shares and stuck-trip sensitivity continue in the
shared reports.

## Relationship to legacy experiments

Round 1 and Round 2 calibrated `BOTH_INSIDE` as a technical preliminary scope.
Their documentation, configs, Java classes and ignored outputs remain as
historical evidence; their parameter vectors are not productive resident
settings. The new pipeline is one reusable architecture and does not create
additional round-specific runner or validator families.

The Open-Tour experiment remains rejected because it relaxed chain-resource
end-of-day consistency and its `ExperiencedPlansService` cohort representation
was incomplete. None of its behavior or entry points is reactivated.

## Reproduction sequence

The shared IntelliJ configurations are:

1. `10 Validate 2019 Resident Mode Choice Configuration` -- read-only config,
   hash, cohort, strategy and target validation; starts no QSim.
2. `11 Run 2019 Resident Mode Choice Iteration-0 Validation` -- server-only
   technical run and automatic output validation. Confirm that both protected
   output directories are absent before starting it.
3. `11B Validate Existing 2019 Resident Mode Choice Iteration-0 Output` --
   read-only recovery validator for an already completed Run-11 directory. It
   starts no controller or QSim and writes the five analysis reports only after
   all validation checks pass.
4. `11C Diagnose Existing 2019 Resident Iteration-0 Main Modes` -- read-only
   diagnosis of physical leg modes versus routing/choice modes in the preserved
   output; starts no controller or QSim.
5. `11D Validate 2019 Resident Iteration-0 48h Horizon Test` -- read-only
   four-override, protected-input, cohort and schedule-horizon preflight.
6. `11E Run 2019 Resident Iteration-0 48h Horizon Test` -- server-only isolated
   iteration-zero execution; it automatically performs the post-run comparison.
7. `11F Compare 43h and 48h Resident Iteration-0 Stuck Events` -- read-only
   recovery comparison for an already completed 48-hour output; it starts no
   controller or QSim.
8. `12 Run Initial 2019 Resident Mode Choice Calibration` -- the only entry
   point that starts the productive iterations 0--20 controller, now with the
   validated 48-hour horizon.
9. `13 Analyze Initial 2019 Resident Mode Choice Output` -- read-only
   standalone all-trip primary and stuck-trip sensitivity analysis after a
   completed protected run.
10. `R1A Validate 2019 Resident Mode Choice Calibration Round 1` -- read-only
    initial-evidence, formula, config-difference, input and cohort validation.
11. `R1B Run 2019 Resident Mode Choice Calibration Round 1` -- server-only
    iterations 0--40 using the protected Round-1 config.
12. `R1C Analyze 2019 Resident Mode Choice Calibration Round 1` -- read-only
    final, sensitivity and iterations-31--40 convergence reporting.
13. `R2A Validate 2019 Resident Mode Choice Calibration Round 2` -- read-only
    Round-1 evidence, cumulative formula, config-difference, input and cohort
    validation.
14. `R2B Run 2019 Resident Mode Choice Calibration Round 2` -- server-only
    iterations 0--60 using the protected Round-2 config.
15. `R2C Analyze 2019 Resident Mode Choice Calibration Round 2` -- read-only
    final, sensitivity and iterations-51--60 calibration-status reporting.

For a new iteration-zero execution, update the repository, run 10, then run 11
manually through IntelliJ with `-Xms4g -Xmx16g`. For the existing university-
server output, do not repeat Run 11: pull this comparison correction, run 10,
then run 11B with `-Xms2g -Xmx8g`. Run 11B reads the fixed protected output
directory, performs no simulation, and writes the five `analysis/` products
only after the complete validation passes. Read its final console status and
all reports. A review-required result must be examined and documented before
Run 12; a failure blocks Run 12. Never delete or overwrite the existing output
merely to repeat QSim. Run 11C does not need to be repeated: preserve and review
its four existing diagnostic products. The corrected Run 11B derives the new
physical/choice summaries directly from the existing final plans and does not
require newly generated iteration-history columns. Do not rerun Run 11. Run 12
was blocked until the iteration-zero evidence and stuck-event audit were
reviewed. That review is complete; the current productive sequence starts with
a fresh Run-10 validation.

For the horizon diagnosis on the university server, preserve the complete
43-hour output, pull the reviewed code, run 10, run 11D, then run 11E with
`-Xms4g -Xmx16g`. Run 11E writes only its new protected 48-hour output and
automatically compares both event files after normal shutdown. If automatic
comparison is interrupted after QSim, run 11F with `-Xms2g -Xmx8g`; do not
repeat 11E. These reports preserve the basis for the later 48-hour decision;
they must not be overwritten.

The initial productive execution is complete and its output must now remain
unchanged. Its Run-13 analysis writes
`resident_mode_choice_final_primary.csv`,
`resident_mode_choice_final_stuck_sensitivity.csv`,
`resident_mode_choice_final_sensitivity_comparison.csv` and
`resident_mode_choice_final_sensitivity_report.md` under `analysis/`, in
addition to the existing final resident summary and report. No further
iteration-zero run is required because no model input or behavioral correction
was made.

For Round 1, pull the reviewed change, preserve the complete initial output,
verify that `output/resident-mode-choice-round-1` is absent, run R1A and require
PASS, run R1B once, then run R1C only after normal completion. Copy the complete
Round-1 `analysis/` directory plus final plans, final-iteration events, output
config and controller log back to the local evidence store.

For Round 2, preserve both earlier outputs, verify that
`output/resident-mode-choice-round-2` is absent, run R2A and require PASS, run
R2B once, and run R2C only after normal completion. A `REVIEW_REQUIRED` result
is substantive calibration evidence and must not trigger an automatic constant
change. Copy the complete Round-2 `analysis/` directory, final plans,
iteration-60 events, output config and controller log back locally.

Local Step-4 preparation created neither protected output directory and ran no
simulation.
