# Mode-choice calibration plan

> **Current productive architecture:** the full regional population remains in
> the simulation. Calibration and primary analysis cover all trips made by the
> tested `MunichResidentClassifier` cohort. Runtime-only subpopulation labels
> restrict mode choice to Munich residents; the productive config is implemented
> but has not yet been run. The existing `BOTH_INSIDE` rounds described below are
> technical preliminary experiments and may support only a secondary
> territorial indicator.

## Purpose and evidence boundary

This document prepares a deliberately small mode-choice calibration for the Munich MATSim project. Mode choice is configured only in a separate protected diagnostic configuration; it has not yet been calibrated or transferred to a production configuration. It separates three types of information:

- **Observed project facts:** the current configuration and population contents reported below.
- **External observations still required:** survey or count targets that are not stored in this repository.
- **Modelling decisions:** the proposed calibration scope, parameters and stopping rule.

The objective is a common behavioural baseline. Only after this common calibration may differences between BAU 2040 and Fast Track 2040 be interpreted as scenario results.

## 1. Recommended calibration year

The provisional recommendation is **2019**, subject to one provenance check before any calibration run. The road-network artifact has a 2019 modification date and the input is the public `munich-v1.0` model, while project documentation explicitly states that the source year of the public population is unresolved. No complete, independently documented 2023 scenario exists: the folder name `munich_base_2023` is not evidence of a 2023 behavioural base, and its optional PT files represent a 2026 service day. Therefore, 2023 must not be used merely because it appears in the directory name.

The repository now contains the structurally and end-to-end validated synthetic 2019 public-transport reference extracted from the approved combined forecast dataset. This resolves the technical PT-input gap but does not turn that supply into an independently sourced historical snapshot. Definitive population and road-network provenance remains required. If the core inputs cannot be defended as a 2019 behavioural baseline, the calibration year remains unresolved rather than being relabelled.

## 2. Observed targets required

The authoritative four-mode trip-share target is car 34%, PT 24%, bike 18%
and walk 24%. It applies to the Munich-resident cohort, subject to a
method-compatible source definition. The previous `BOTH_INSIDE`, `ALL_PLANS`
application is retained only as preliminary evidence. The values originate
from the thesis external-cost dataset, but the researcher must still add its
detailed source reference. The remaining external target package must provide:

- mean trip distance and mean door-to-door travel time by mode;
- sample weights, exact boundary treatment, handling of inbound/outbound trips and treatment of respondents without a complete day;
- car ownership, driving-licence and household vehicle availability distributions, preferably linked to person or household types;
- uncertainty intervals or at least survey sample sizes.

Targets from different years or definitions must not be silently combined. Counts may be used as an additional traffic-volume check, but they are not a substitute for person-trip modal shares.

The authoritative annual passenger-kilometre values are car 10,637.49 million,
PT 4,510.08 million, bike 1,131.50 million and walk 620.50 million. Their
exact normalized shares are 62.945329%, 26.687543%, 6.695437% and 3.671691%, respectively.
The rounded 63/27/7/4 figures sum to 101% and must not be used as exact
targets. Absolute annual values are not directly comparable to one simulated
reference day until an annualisation rule is approved. Vehicle-km are never
mode-choice targets.

## 3. Modal-split definition

The calibration metric is the share of **main-mode trips between consecutive main activities** in selected plans. Stage activities such as `pt interaction` are excluded. A routed PT journey with access and egress legs counts once as `pt`; it is not counted as several walk and PT legs. MATSim's standard analysis main-mode identification is applied to all physical legs of a trip. `ride` is not a separate calibration category under the approved aggregate-car approximation. Unknown results are reported explicitly. The same algorithm must be used for observations and simulation exports.

The primary cohort consists of 68,770 classified Munich residents, and all 137,540 of their main
trips remain eligible even when one or both endpoints are outside Munich. The
full regional population remains in the simulation and continues to affect
traffic and public-transport conditions. Residence will later be classified
from the home activity with the municipal boundary; missing or ambiguous home
information fails closed and is reported. The 107,618 persons without an
identifiable home remain unresolved background traffic and are not inferred to
be commuters or non-residents. The existing two-endpoints-inside filter
is documented in `munich_spatial_analysis_scope.md` as preliminary and as a
possible secondary territorial indicator.

The current population statistic in this document is a simpler **input-plan leg share**, not a calibrated or observed modal split. The base population has one direct leg per trip and no routed stages, so it is a useful readiness diagnostic but must not be confused with a post-routing output statistic.

## 4. Initial modes

The first calibration allows `car`, `pt`, `walk` and `bike`. These four modes are canonical and present in the input population. `ride` and `other` are not offered as choice alternatives. Standard MATSim `ride` would represent an unlinked passenger trip without a matched driver or vehicle; the project has neither a joint-trip or ride-matching model nor a compatible trip-based passenger target. `car` therefore approximates aggregate motorised individual passenger travel. For the later external-cost calculation, simulated car passenger-kilometres will be converted to vehicle-kilometres with an observed 2019 occupancy factor (`Pkm/Fkm`). That factor must be held constant in BAU and Fast Track; an alternative 2040 occupancy may only be a sensitivity.

Car and bike are chain-based in the MATSim 2025.0 default `SubtourModeChoice` configuration. This preserves vehicle continuity over a closed subtour. Bike remains teleported in the current project; its constant can be calibrated, but network-specific cycling measures still cannot be evaluated credibly without a separate calibrated bicycle network and router.

## 5. Productive technical configuration

The productive architecture is defined by
`scenarios/munich_calibration_2019/config_resident_mode_choice_calibration.xml`.
After loading the unchanged population, it assigns `munich_resident` (68,770),
`regional_background` (147,655) and `unresolved_background` (107,618) in
memory. Residents receive ChangeExpBeta/ReRoute/SubtourModeChoice weights
0.8/0.1/0.1. Both background groups receive only ChangeExpBeta/ReRoute weights
0.9/0.1 and cannot change mode. There is no unscoped strategy.

The config retains SwissRailRaptor, `car,pt,walk,bike`, chain-based `car,bike`,
the stable `fromSpecifiedModesToSpecifiedModes` behavior, seed 4711, five-percent
capacity factors and iterations 0--20. All four initial constants are zero.
Output uses `failIfDirectoryExists`. Step 3 validated this architecture without
starting a controller; Step 4 must add and run the protected iteration-zero
validation before the initial calibration is authorized.

## 6. Parameters to calibrate

The first calibration round changes only the alternative-specific mode constants for `car`, `pt`, `walk` and `bike`. One constant must be fixed as the reference (recommended: `car = 0`) so that the remaining constants are identifiable. The `pt`, `walk` and `bike` constants are then adjusted to reduce the difference between simulated and observed shares.

The approved provisional setting is `considerCarAvailability=false` because the base population has no `carAvail`, licence or vehicle-availability attributes. This avoids inventing attributes but offers car without an individual availability constraint and must be reported as a strong limitation. Later sensitivity work should use defensible ownership and licence evidence rather than infer attributes from simulated mode choices.

Calibration round 1 keeps car as the reference with constant 0.00 and changes
only the alternative-specific constants to PT 0.89, walk 0.78 and bike -0.21.
These researcher-approved values are a first ratio-guided response to the
uncalibrated final shares (approximately 41.30% car, 13.84% PT, 26.71% bike
and 18.16% walk) relative to the 34/24/18/24 targets. They are a heuristic
first step, not final estimates and not a claim that a single round will meet
the targets.

Round 1 was structurally valid and moved the shares in the intended direction,
but it was not converged. Its late-window values and evidence hashes are
preserved in `docs/calibration/mode_choice_calibration_history.csv` and
`docs/calibration/mode_choice_calibration_round_1.md`. Between iterations 16
and 20, car increased from 35.506809% to 39.413336%, while walk decreased from
22.779774% to 18.408747%. The five-iteration mean is not a stable endpoint.

Round 2 keeps car at 0.00 and uses PT 1.27, walk 1.27 and bike -0.34. This is a
second ratio-guided step based on the remaining discrepancy in the mutable
population. It is not a Pkm-based adjustment or a final parameter estimate.

## 7. Parameters initially held fixed

Initially retain the existing scoring and routing values: marginal utility of travel time of -6 utils/hour for each configured mode, zero marginal distance utility, zero monetary distance rate, performing utility 6 utils/hour, PT waiting utility -6 utils/hour and line-switch utility -1. Keep the current teleported speeds and beeline factors (`bike` 4.167 m/s, `walk` 0.833 m/s, factor 1.3), `networkModes=car`, SwissRailRaptor defaults and the existing activity parameters.

Time, distance, money, parking, fares, transfer penalties and value-of-time parameters should change only when an empirical source or a specific behavioural hypothesis justifies them. Otherwise, mode constants would no longer be the only adjustment and parameter compensation would become difficult to interpret.

## 8. Simulation–observation comparison

For each parameter vector, observations and simulation exports must use
the same Munich-resident cohort and main-trip definition. The productive analyzer
uses complete selected scenario plans at `AfterMobsim`, includes all resident
trips, excludes both background groups and reports `BOTH_INSIDE`, `ORIGIN_ONLY`,
`DESTINATION_ONLY`, `BOTH_OUTSIDE` and invalid-coordinate trips secondarily.
Use the mean of several stable late iterations rather
than a single noisy iteration. Door-to-door travel time remains a later
extension and is not inferred by the present distance analyzer.

The current uncalibrated input-plan leg shares across all 324,043 persons are: car 45.319%, PT 10.828%, walk 29.788% and bike 14.064% (540,468 legs). They describe the synthetic input, not observed Munich behaviour and not a model prediction.

## 9. Iterative calibration procedure

Use a transparent coordinate-descent procedure:

1. run the confirmed baseline with the initial constants;
2. compute share errors in percentage points for the four modes;
3. adjust one non-reference constant at a time in the direction of its error, using small fixed steps (for example 0.25 utils, halved after an overshoot);
4. rerun from the same initial population and random seed;
5. repeat until the stopping rule is met;
6. repeat the final vector with at least two additional random seeds to check robustness.

Every run must record the complete parameter vector, input hashes, seed, iteration window and resulting metrics. A more complex optimiser or an eqasim pipeline is not justified for the first calibration stage.

For round 1, compare the mean, minimum and maximum modal shares over iterations
16--20 with the target vector. A second round is warranted if a late-iteration
mean remains materially outside the 1--2 percentage-point tolerance, if the
late values still show a systematic trend, or if improved share agreement is
accompanied by implausible Pkm shares, distances or a material stuck-event
pattern. A small positive stuck count alone is recorded rather than treated as
automatic failure; modes, persons and timing near the 43-hour QSim horizon must
be inspected without inferring a cause from `PersonStuckEvent`.

Round 2 uses iterations 0--40 and disables innovation after 60% of the run.
This leaves approximately 16 iterations for selection and stabilisation among
existing plans. Its primary analysis window is iterations 31--40. For each
mode, report the mean, minimum, maximum, range and least-squares linear trend
in percentage points per iteration. A range of at most 1 percentage point and
an absolute trend of at most 0.1 percentage points per iteration are working
convergence criteria, not universal MATSim thresholds. Exceeding them is a
calibration result rather than a technical run failure. Target accuracy of
1--2 percentage points is reported separately. Constants alter modal utility,
whereas the post-innovation period tests stability; these functions must not
be conflated.

The legacy target CSV at
`original-input-data/calibration/mode_choice_targets_2019.csv` preserves the
preliminary `BOTH_INSIDE` workflow. The productive resident pipeline validates
its targets in `ResidentModeChoiceCalibrationTargets`: primary trip shares,
absolute annual Pkm references and exact normalized secondary Pkm shares remain
distinct from external-cost Fkm references. The analyzer does not alter
constants. Full resident definitions are documented in
`resident_mode_choice_calibration.md`.

## 10. Stopping rule

Stop when each main-mode share is within 1–2 percentage points of its observed target, no mode shows a systematic late-iteration trend, and mean distances and travel times are plausible relative to observations. If share agreement requires implausible travel times or very large constants, stop and diagnose input scope, PT year, car availability or routing before adding parameters.

## 11. Transfer to BAU and Fast Track

After validation, freeze exactly the same calibrated scoring parameters, mode-choice settings, eligible modes, random-seed policy and analysis definition in BAU and Fast Track. Do not recalibrate BAU and Fast Track to separate modal-split targets. Scenario-specific supply and demand changes must be allowed to produce their own differences under one common behavioural model.

Future modal-split differences are interpretable as scenario outputs only after this common baseline calibration. Before that point, apparent differences reflect uncalibrated configuration and cannot support causal or forecast claims.

## 12. Methodological limitations and readiness finding

The population is technically clean but only partly ready. All 324,043 persons have exactly one selected plan; all 540,468 main-trip modes are canonical; activity/leg alternation and coordinates are complete; and no unknown modes occur. The residence audit identifies 68,770 Munich residents, all with a closed subtour and all technically eligible for the configured strategy. The 147,655 classified non-residents and 107,618 unresolved no-home persons remain background traffic without mode-changing strategies. Every selected plan is initially monomodal, and no availability attributes exist. The productive safeguards are documented in `resident_mode_choice_calibration.md`.

Among the open plans, 75,149 end with the same activity type at a different
location and 32,469 end with a different activity type; no missing/problematic
endpoint was found. These patterns are consistent with possible day-edge or
incomplete diary chains, but that interpretation is not observed directly.
The exploratory `betweenAllAndFewerConstraints` test is not adopted because it
relaxes end-of-day location consistency for chain-based car and bike. Its
former `ExperiencedPlansService` representation found identifiers but zero
current trips, so it provides no evidence for changing the behavior. The
 37,417 fixed `BOTH_INSIDE` trips remain a limitation of the preliminary
territorial experiment, not a definition of the resident cohort.

The first completed run preserves only iteration 20 because standalone postprocessing replaced its history. Its final trip shares are car 41.295763%, PT 13.843940%, bike 26.705034% and walk 18.155262%. These are valid final-state diagnostics, not evidence of convergence.

The synthetic 2019 PT reference passed the full 324,043-person iteration-zero
server validation on 24 August 2026, but the broader baseline-year provenance
remains the main substantive uncertainty. Round 1 is structurally valid,
directionally useful and not converged. Round 2 was prepared as another
`BOTH_INSIDE` diagnostic. Neither vector is a final calibrated result. The
resident-based architecture now replaces their primary scope, but its Step 4
iteration-zero validation and subsequent calibration runs remain outstanding.
