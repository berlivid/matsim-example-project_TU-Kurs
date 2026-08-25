# Munich-resident analysis scope

## Methodological purpose

The future 2019 mode-choice calibration and the primary thesis analysis use a
residence-based cohort. The complete regional MATSim population remains in the
simulation, but the calibration statistics will cover every main trip made by
a person classified as a Munich resident. This preflight defines and audits the
cohort only. It does not activate the cohort in a simulation or calibration.

## Residence definition

A person is a Munich resident when their selected plan contains an identifiable
main activity with the exact type `home` whose valid coordinate is covered by
the City of Munich municipal boundary. The boundary is stored in
`original-input-data/munich-demography/munich_boundary.json`, uses EPSG:31468
and is evaluated with JTS `covers`. A coordinate exactly on the administrative
boundary therefore counts as inside Munich.

Before implementation, the activity types in all selected plans of the
authoritative 2019 calibration population were inspected in one streaming pass.
The population contains 324,043 persons and selected plans. The observed main
activity frequencies were:

| Activity type | Activities |
|---|---:|
| `home` | 432,850 |
| `work` | 81,180 |
| `education` | 18,878 |
| `shopping` | 40,307 |
| `other` | 291,296 |

Only exact, case-sensitive `home` is accepted. No additional home variant was
present, so no variant is included. In particular, no substring rule is used.
MATSim's official stage-activity predicate excludes interaction activities
before home identification; a stage activity can never establish residence.
Residence is not inferred from an arbitrary trip origin or destination.

## Multiple, missing and conflicting homes

Every person receives exactly one of six classifications:

- `MUNICH_RESIDENT`
- `NON_MUNICH_RESIDENT`
- `NO_HOME_ACTIVITY`
- `MISSING_HOME_COORDINATE`
- `CONFLICTING_HOME_LOCATIONS`
- `INVALID_SELECTED_PLAN`

Multiple exact home activities are classified normally when every valid home
coordinate produces the same inside/outside result. They need not be identical
coordinates. No spatial distance tolerance is used for residence: exact JTS
`covers` results are decisive. If home coordinates imply both an inside and an
outside location, the person is flagged as `CONFLICTING_HOME_LOCATIONS`. If at
least one exact home lacks a finite coordinate, the result is
`MISSING_HOME_COORDINATE`. A missing or structurally unusable selected plan is
`INVALID_SELECTED_PLAN`. These unresolved cases are not assigned silently to
either cohort.

The separate closed-subtour readiness diagnostic uses a tolerance of 1e-6
metres to recognize repeated coordinates in raw plans that do not yet have link
identifiers. This technical tolerance has no effect on residence.

## Residence-based versus territorial analysis

Residence-based analysis follows people: once a person is classified as a
Munich resident, all their main trips are in scope. This includes trips that
cross the municipal boundary and trips made entirely outside Munich. Excluding
such trips would understate the mobility behavior and modal choices of Munich
residents and would make the population definition depend on individual trip
destinations.

Territorial analysis instead follows trip locations. The former preliminary
calibration used `BOTH_INSIDE`, meaning that both main-activity endpoints had to
be inside or on the Munich boundary. `BOTH_INSIDE` remains useful as a secondary
indicator of travel within the city, but it is not the resident cohort and is
not the final primary calibration scope. The resident preflight reports five
territorial categories for resident trips: `BOTH_INSIDE`, `ORIGIN_ONLY`,
`DESTINATION_ONLY`, `BOTH_OUTSIDE` and `INVALID_OR_MISSING_COORDINATE`.

## Why non-residents remain in the simulation

People living outside Munich still use the same roads and public-transport
services. Their trips contribute to congestion, crowding, travel times and the
network conditions experienced by Munich residents. Removing them would change
the simulated environment and weaken the interpretation of scenario effects.
The full regional population therefore remains background traffic even though
non-resident trips do not enter the future resident-based calibration targets.

## Reproducible read-only preflight

`AnalyzeMunichResidentCohort` resolves the population from the authoritative
`scenarios/munich_calibration_2019/config_mode_choice_calibration.xml` rather
than maintaining a second population path. It reads persons streamingly and
writes only diagnostic files under
`generated/munich_resident_cohort_preflight/`. The unresolved-person file is
limited to the 100 lexicographically smallest unresolved person IDs; complete
classification totals remain in the summary.

This step does not write or filter a population. It changes no behavioral
parameter, calibration constant, mode-choice strategy, config, network,
schedule, vehicle file or scenario input. BAU and Fast Track remain untouched.
