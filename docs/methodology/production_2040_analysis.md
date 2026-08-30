# Shared production analysis for BAU 2040 and Fast Track 2040

## Purpose and comparative design

This specification defines one parameterised analysis pipeline for the later BAU 2040 and Fast Track 2040 production runs. The scenario ID selects only the approved config, run identity and output directory. Metric definitions, territorial scope, scaling, late window and quality gates are identical. No BAU--Fast Track comparison is produced here; comparison is released only after both production runs and both scenario analyses have independently passed.

The pipeline consists of `Production2040AnalysisSpec`, `Production2040AnalysisListener`, `AnalyzeProduction2040Output`, `Production2040VehicleMetrics` and `ValidateProduction2040AnalysisOutput`. A future runner installs the listener with `Production2040AnalysisListener.install(controler, "BAU")` or `"FAST_TRACK"`. The postprocessor and validator each accept exactly one of those scenario identifiers. None of these read-only entry points starts Controller or QSim.

## Simulation population and territorial analysis population

The simulation retains the complete regional five-percent population. This is necessary because trips outside Munich still affect congestion, routes and public-transport operations. The central result sample is narrower: a main trip is included only if both its origin main activity and destination main activity are inside or on the Munich municipal boundary (`BOTH_INSIDE`). The established boundary predicate is `covers`, and the canonical UTF-8/LF SHA-256 must remain `EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26`.

MATSim's stage-activity identifier is used when trips are constructed. Public-transport interactions and transfers therefore remain parts of one main trip. The standard MATSim analysis main-mode identifier assigns `car`, `pt`, `bike` or `walk`. Any other main mode is reported and causes validation to fail; it is never silently reassigned. Missing or non-finite endpoint coordinates also fail validation.

The historical 160,603 `BOTH_INSIDE` trips describe the 2019 calibration sample only. The 2040 denominator is derived separately from each final scenario population. BAU and Fast Track may consequently have different absolute trip counts because their approved populations differ.

## Iteration statistics and stability

The runtime listener records a compact selected-plan snapshot at every iteration from 0 through 60. It writes no additional large events file. Each snapshot contains the `BOTH_INSIDE` denominator, counts and unscaled shares for the four main modes, and any unexpected modes. In parallel it counts all PersonStuckEvents, events affecting persons with at least one `BOTH_INSIDE` trip, and unique affected persons by iteration and leg mode.

Iterations 51--60 are the binding late window. For every main mode the pipeline reports mean, minimum, maximum, range and an ordinary least-squares linear trend in percentage points per iteration. The common transparent quality bounds are an absolute trend below 0.10 percentage points per iteration, a range no greater than 2.0 percentage points and a maximum late iteration-level stuck-person incidence no greater than 0.10% of the `BOTH_INSIDE` denominator. These are project criteria, not universal MATSim thresholds.

The listener writes recoverable intermediate CSVs below `analysis-runtime/`. They are not final results. The postprocessor validates the complete run and constructs all ten final files in memory before atomically publishing a new `analysis/` directory. It refuses to overwrite an existing analysis directory.

## Trips, modal split, Pkm and travel time

Final modal split uses all final selected-plan `BOTH_INSIDE` main trips. Trip counts are shown at sample scale and expanded by factor 20; shares are not scaled. The four counts plus unexpected modes must reconcile exactly with the observed scenario-specific denominator.

Distance and time measurements use MATSim's final standard `output_trips` records, matching the validated Round-5 definition. `traveled_distance` is the complete routed main-trip distance, summed across its legs. For a PT main trip it includes access walk, in-vehicle legs, transfer walk and egress walk. It is not Euclidean distance, pure in-vehicle PT distance or an event-derived distance. Missing values are reported; no straight-line substitute is invented. Each main mode reports sample and expanded Pkm, Pkm share, mean and median main-trip distance, mean travel time, record coverage and the missing/invalid record count. At least 99% mode-specific measurement coverage is required. Shares, means, medians and travel times are never multiplied by 20.

## Private-car vehicle-kilometres

Car Fkm are streamed once from the final events file against the network actually written by the run. A `VehicleEntersTraffic` event contributes the remaining fraction of the first link, every `LinkEnter` contributes that link exactly once, and `VehicleLeavesTraffic` removes the untravelled fraction of the final link. This is the relative-position convention implemented by MATSim 2025.0 and prevents first-/last-link omission and person-event double counting.

Only non-transit vehicles whose network mode is `car` are included. Transit vehicles identified by `TransitDriverStarts` are excluded even though they use car-network links. Sample Fkm and factor-20 expanded private-car Fkm are shown separately. The number of included and unassigned vehicles and unresolved links is reported. Car Pkm/Fkm is a plausibility ratio, not an identity: passengers and vehicles measure different concepts.

## Public-transport submodes

The four principal PT route modes are `bus`, `tram`, `subway` and `rail`; established synonyms are normalised only to these explicit categories. Each `TransitDriverStarts` event must resolve to a line, route, departure and transit vehicle, and its route `transportMode` determines the submode. Facility events must resolve to schedule stops. Other valid schedule modes, such as a retained ferry service, are reported as separate rows and are never silently reassigned to a principal mode. Missing route, departure, stop or vehicle references fail validation.

For PT Pkm, main-activity-end events advance each person's final selected-plan main-trip index. Boarding and alighting events identify the actual access and egress stops used while that passenger is inside a transit vehicle on a `BOTH_INSIDE` main trip. The routed in-vehicle distance between those stops is calculated from the referenced transit route and network and accumulated by route mode. It is therefore routed in-vehicle distance between event-observed stops, not Euclidean distance or a claim about the passenger's exact within-link standing position. Access and egress walks contribute no PT Pkm, and a bus--subway transfer partitions in-vehicle Pkm between bus and subway without creating another main trip. Boardings are also reported by route mode.

PT Fkm count each transit vehicle's actual link-event distance once, regardless of its passenger count. The five-percent factor describes private demand and road capacity, while the transit schedule is supplied at full service scale. PT Fkm therefore remain at observed full-service scale and are not multiplied by 20. This distinction prevents a fictitious twentyfold multiplication of scheduled service.

## Output files

After all checks pass, each scenario's protected output receives exactly:

- `iteration_mode_shares.csv`
- `late_iteration_statistics.csv`
- `final_main_mode_summary.csv`
- `final_pkm_by_main_mode.csv`
- `final_car_fkm.csv`
- `final_pt_pkm_by_route_mode.csv`
- `final_pt_fkm_by_route_mode.csv`
- `stuck_events_by_iteration_and_mode.csv`
- `analysis_quality_checks.csv`
- `analysis_report.md`

Every CSV identifies the scenario, sample factor, unit, observed value, expansion where methodologically applicable, coverage and definition. Intermediate data are not presented as valid final results.

## Fail-closed validation and reproducibility

Before reading results, the validator requires the approved paired production configs to pass, all protected manifest hashes and the canonical boundary hash to match, the scenario output and run ID to be exact, the output config to be semantically identical to the approved config, and the log to contain normal-shutdown evidence. It requires final plans, trips, events, network, schedule and transit vehicles; iterations 0--60; the complete late window; and one compact stuck summary for every iteration. It rejects smoke, calibration and wrong-scenario outputs by exact path, run ID and config identity.

Final checks cover selected-plan structure and coordinates, mode and trip sums, finite non-negative Pkm/Fkm, coverage, PT references, separately reported non-principal PT route modes, stuck incidence and protected-input hashes before and after analysis. Final publication is atomic and refuses overwrite. The production configs already write final events, plans and trips at normal shutdown under MATSim 2025.0; no config or behavioural parameter change was required.

The Round-5 iteration artifact is used read-only as regression evidence. The common trend/statistics implementation reproduces its documented late modal means. No calibration output is changed or republished. No production output yet exists, so this task does not execute the production postprocessor.

These 2040 results remain scenario projections transferred from the frozen 2019 behavioural calibration. They are not recalibrated against unavailable 2040 observations, and remaining calibration and distance limitations continue to constrain absolute interpretation.

## Runner integration and recovery

`RunMatsim2040Production BAU|FAST_TRACK` is the only full-run entry point. It loads the corresponding approved config without overrides, installs the project's established SwissRailRaptor module, and calls `Production2040AnalysisListener.install(controler, scenario)` exactly once. After normal Controller shutdown it runs the common postprocessor and then validates the published analysis package. A PASS is emitted only after all three stages have succeeded.

Smoke runs use the same scenario network, schedule, vehicles, routing, scoring, QSim and time settings but iteration 0 and four in-memory technical agents. Their automatic smoke validator is separate from this scientific pipeline: smoke modal shares, trip totals, Pkm and Fkm are not research results and must never enter a BAU--Fast Track comparison.

`AnalyzeExistingMatsim2040ProductionOutput BAU|FAST_TRACK` is the recovery entry point. It has no Controller or QSim path. It accepts only a normally completed output with the exact approved run ID and output config, complete iterations 0--60, complete runtime listener evidence and protected hashes. If no final analysis exists, it reruns postprocessing once; if a complete analysis exists, it validates it without overwriting. Partial or contradictory publication remains fail-closed and must be investigated rather than silently replaced.

The comparison stage remains unreleased until both independently produced and validated analysis packages exist. BAU is completed and validated before Fast Track is started, so server contention cannot become an undocumented scenario difference.
