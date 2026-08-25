# Mode-choice calibration output analysis

> **Scope status:** this document describes the analyzer used by the technical
> `BOTH_INSIDE` calibration experiments. The final thesis calibration and
> primary analysis will retain the full regional population and cover all trips
> made by Munich residents. Residence will later be identified from the home
> activity; that classification is not implemented yet. `BOTH_INSIDE` remains
> available as a possible secondary territorial indicator.

## Purpose and analytical boundary

The calibration analyzer measures MATSim results; it does not adjust mode
constants, run an optimizer, or change a population or scenario input. It is
used for every iteration of the synthetic-2019 diagnostic and can also be run
as a read-only postprocessor. The same definitions are intended for the later
BAU and Fast Track comparison after one common behavioural calibration.

The implemented preliminary sample is `BOTH_INSIDE`: a main trip is included
when both its origin and destination main activities are inside or on the
administrative City of Munich boundary. The implementation reuses the
unchanged `MunichMunicipalBoundary` and `MunichTripBoundaryFilter`; JTS
`covers` includes boundary points. The regional population remains in the
simulation. Inbound and outbound trips form the diagnostic
`BOUNDARY_CROSSING` group, all trips form `ALL_TRIPS`, and wholly external or
invalid-coordinate trips remain visible as controls. None of these categories
currently identifies Munich residents.

## Trip and mode definitions

A main trip connects two consecutive main activities. MATSim's
`TripStructureUtils` stage-activity predicate removes access, interaction,
transfer and egress activities from the trip endpoints. Each valid main trip
therefore enters the modal split exactly once. A public-transport journey with
access, one or more transfers and egress is one `pt` main trip. The calibrated
main modes are `car`, `pt`, `walk` and `bike`; they sum to 100 percent when no
unknown main mode is present. Unknown modes are reported explicitly and never
silently discarded. Their share uses the same all-main-trip denominator, so
the four calibrated shares deliberately sum to less than 100 percent if an
unknown mode occurs. `ride` is not an endogenous choice alternative.

Results are split into `ALL_PLANS`, `MODE_CHOICE_CAPABLE` and
`NOT_MODE_CHOICE_CAPABLE`. Capability means that MATSim identifies at least
one closed subtour under the configured chain-based rules. This separation is
necessary because an open plan cannot be changed by the current
`SubtourModeChoice` strategy even though its trips still contribute to network
conditions and aggregate results.

## Two passenger-kilometre perspectives

Main-mode trip metrics support calibration against conventional trip surveys.
The complete door-to-door main-trip distance is assigned to its one main mode.
Thus, all stages of a PT main trip contribute to the main-mode `pt` distance.
Modal shares, mean trip lengths and main-mode passenger-kilometres use this
perspective.

Physical-stage passenger-kilometres support later cost accounting without
double counting PT access and egress. Each stage is assigned to `car`, `walk`,
`bike`, `bus`, `tram`, `subway`, `rail`, `ferry`, `unknown_pt` or
`unknown_stage`. PT submodes come from the transport mode of the actually used
MATSim `TransitRoute`; line-name heuristics are not used. Access and egress
walk stages remain walk kilometres and are not additionally counted as
in-vehicle PT kilometres.

## Distance hierarchy and quality control

Every physical leg is measured with the first available defensible source:

1. a finite and plausible distance stored on the route;
2. for a `NetworkRoute`, the reproducible sum of its start, intermediate and
   end link lengths;
3. for a `TransitPassengerRoute`, the path between the used access and egress
   stops on the referenced transit line and route;
4. for teleported walk or bike, its stored route distance;
5. as a documented last resort, the endpoint beeline distance multiplied by
   the configured mode-specific beeline factor.

Negative, non-finite, implausible or unreconstructable distances are errors;
they are counted and are not converted to zero. `distance_quality.csv` reports
the number and passenger-kilometres by source together with invalid stage and
main-trip counts. Main-trip distance is valid only when every physical stage
has a valid distance.

## Iteration lifecycle and reproducibility

`ModeChoiceCalibrationIterationListener` runs at MATSim's `AfterMobsim`
lifecycle event. MATSim 2025.0 performs the current iteration's replanning
before mobsim and the next iteration's replanning only after this point. The
listener therefore reads every person's `selectedPlan` from the scenario: this
is the complete selected and routed plan snapshot used as input to the current
mobsim. It is not labelled as wholly experienced, because a stuck agent may not
execute its full plan. `ExperiencedPlansService` is unsuitable here because it
reconstructs plans only from experienced activity and leg events; the open-tour
test demonstrated that this can leave plans empty or incomplete.

The preliminary listener fails closed unless the unchanged structural reference counts are
324,043 selected persons, 540,468 main trips and 160,603 `BOTH_INSIDE` main
trips. It writes deterministic, sorted, small files to the protected output
directory. `AnalyzeModeChoiceCalibrationOutput` uses the complete final
`output_plans` snapshot rather than `output_experienced_plans`. The postprocessor reads existing plans, network
and transit schedule and writes only below that output's `analysis` folder.
Listener output and standalone output now have separate write paths. The
listener retains one sorted metric block for each unique analyzed iteration.
Standalone postprocessing may refresh final-state products but treats an
existing iteration-history file as immutable; if no history exists, it reports
that absence instead of manufacturing earlier iterations.

The generated files are:

- `analysis/mode_choice_iteration_metrics.csv`: long-format metrics for every
  iteration, spatial scope and plan-eligibility group;
- `analysis/mode_choice_final_summary.csv`: final-iteration main-mode table;
- `analysis/pt_passenger_km_by_submode.csv`: physical PT-stage kilometres;
- `analysis/distance_quality.csv`: source and invalid-distance diagnostics;
- `analysis/stuck_events_iteration_metrics.csv`: separate event/person counts
  by iteration, mode and transparent QSim-end time window;
- `analysis/calibration_target_comparison.csv`: optional compatible target
  comparisons; and
- `analysis/analysis_report.md`: concise methodological run summary.

Passenger-kilometre sums are reported both as `unscaled_5pct_pkm` and with the
uniform population factor 20. Modal shares and mean trip lengths are never
scaled. No daily-to-annual conversion is performed.

## Car kilometres and occupancy

Car main-mode passenger-kilometres represent aggregate motorised individual
passenger travel. `raw_matsim_car_km` is kept as a separate route-distance
diagnostic for the modelled car stages. It must not be called
external-cost-compatible vehicle-kilometres. No occupancy correction is made
until compatible observed 2019 values are supplied. The later calculation is:

`occupancy_2019 = observed_car_pkm_2019 / observed_car_fkm_2019`

`external_cost_car_fkm_scenario = simulated_car_pkm_scenario / occupancy_2019`

The same occupancy factor must be retained in BAU and Fast Track; a different
2040 occupancy may only be a documented sensitivity.

## Empirical targets and limitations

`original-input-data/calibration/mode_choice_targets_2019.csv` is the
versioned target and reference schema. The authoritative 2019 trip-share
target is car 34%, PT 24%, bike 18% and walk 24%. It will be applied to the
future resident cohort once that scope is implemented. The current CSV still
contains preliminary `BOTH_INSIDE` scope metadata and must not be mistaken for
an implemented resident classifier. The source is identified as the thesis
external-cost dataset; its detailed citation remains to be completed.

The authoritative annual passenger-kilometre values are:
10,637.49 million car, 4,510.08 million PT, 1,131.50 million bike and 620.50
million walk Pkm per year. Their normalized target shares are 62.9453%,
26.6875%, 6.6954% and 3.6717%. The rounded 63/27/7/4 figures sum to 101% and
must not be used as exact targets. Absolute annual Pkm are not directly
compared with a simulated service day without an approved annualisation rule.

The car reference of 10,637.49 million Pkm and 7,091.66 million vehicle-km
implies exactly 1.5 persons per vehicle. PT vehicle-km are 65.87 million per
year. These vehicle-km values are external-cost references, not mode-choice
targets, and must not be mixed with Pkm. Mean trip-distance targets remain
blank. A numerical calibration comparison is made only where year, unit,
spatial scope, plan eligibility, target universe and trip definition are
compatible.

## First-run history limitation

The locally copied first-run analysis contains iteration 20 only. Its
`mode_choice_iteration_metrics.csv` and `mode_choice_final_summary.csv` are
byte-identical. The former standalone postprocessor called the listener writer
with a single result, which replaced the historical file. No standard events,
plans, score statistics or mode-choice history accompanied the copied folder,
so iterations 0-19 cannot be reconstructed. Iteration 20 is a valid final-state
observation, but convergence of that run cannot be assessed.

The open-tour test history contains iterations 0--5, but its plan metrics used
the incomplete former `ExperiencedPlansService` source. The finding of 107,618
cohort identifiers with zero current trips makes that cohort diagnosis
non-interpretable. Its 8,465 cumulative stuck events remain valid event facts
and must be audited separately; their causes are not encoded by
`PersonStuckEvent`.

Future runs count stuck events separately per iteration and cumulatively,
including unique persons, leg mode, minimum/maximum event time and whether the
event occurs in the final hour before the configured 43-hour QSim end or after
that end. The one-hour band is descriptive, not causal. The read-only auditor
deduplicates root and iteration event files, reports missing event iterations
and lists frequent links without inferring why a person became stuck.

The analyzer cannot remedy uncertain baseline provenance, missing ownership
and licence attributes, open plans, or an uncalibrated mode-choice model. It
does not establish causality and does not itself calibrate a parameter. Its
purpose is to make the later empirical comparison reproducible and auditable.
