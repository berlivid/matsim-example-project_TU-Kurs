# Resident-based 2019 mode-choice calibration

## Status and purpose

This document defines the productive calibration architecture prepared in Step
3. The architecture is locally compiled and validated, but no controller,
MATSim mobility simulation or QSim has been started. The next task must first
perform a protected iteration-zero validation before the initial iterations
0--20 calibration is authorized.

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
population, schedule, vehicles, 43-hour horizon, 0.05 flow/storage capacity
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
Per iteration it records resident persons and trips, trip counts and shares,
route-distance passenger-kilometres and shares, target differences, the five
spatial categories, resident stuck events by mode, and late-iteration means,
ranges and linear trends. Selected-plan metrics are planned/routed outcomes;
stuck events remain a separate execution diagnostic.

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
2. `11` -- intentionally reserved for the protected iteration-zero validation
   runner to be implemented in Step 4.
3. `12 Run Initial 2019 Resident Mode Choice Calibration` -- the only entry
   point that will start the iterations 0--20 controller; do not run before
   Step 4 approval.
4. `13 Analyze Initial 2019 Resident Mode Choice Output` -- read-only
   standalone selected-plan analysis after a completed protected run.

Step 3 created no calibration output directory and ran no simulation.
