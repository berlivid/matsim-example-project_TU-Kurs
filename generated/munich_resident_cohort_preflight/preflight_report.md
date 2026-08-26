# Munich-resident cohort preflight

## Purpose and method

This read-only preflight identifies the cohort for the future 2019 calibration and primary thesis analysis. A person is a Munich resident when the selected plan contains at least one exact `home` main activity and all valid `home` coordinates give the same inside result under the City of Munich municipal boundary. JTS `covers` includes points on the boundary. MATSim stage activities are excluded with its official stage-activity predicate. The inspected selected plans contained the main activity types `home`, `work`, `education`, `shopping` and `other`; only exact, case-sensitive `home` is accepted. No substring or inferred trip-end rule is used.

The residence rule uses no distance tolerance. Multiple valid home coordinates may differ spatially when they all imply the same inside/outside result. Contradictory inside/outside results are unresolved. The separate 1e-6 metre tolerance reported for closed-subtour readiness only recognizes repeated raw-plan coordinates and does not affect residence.

Residence-based analysis includes every main trip made by a classified Munich resident, including boundary-crossing and entirely external trips. The former `BOTH_INSIDE` scope remains a secondary territorial indicator. Regional non-residents remain in the simulation as background traffic because they contribute to congestion, public-transport demand and network conditions.

## Sources

- Authoritative calibration config: `scenarios\munich_calibration_2019\config_mode_choice_calibration.xml`
- Population resolved from that config: `scenarios\munich_base_2023\munich-v1.0-5pct.plans.xml`
- Municipal boundary: `original-input-data\munich-demography\munich_boundary.json`
- Boundary SHA-256: `EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26`
- Coordinate reference system: EPSG:31468

## Classification results

Persons: 324043; selected plans: 324043; total main trips in the complete regional population: 540468.

| Classification | Persons | Share |
|---|---:|---:|
| MUNICH_RESIDENT | 68770 | 21.222492% |
| NON_MUNICH_RESIDENT | 147655 | 45.566483% |
| NO_HOME_ACTIVITY | 107618 | 33.211024% |
| MISSING_HOME_COORDINATE | 0 | 0.000000% |
| CONFLICTING_HOME_LOCATIONS | 0 | 0.000000% |
| INVALID_SELECTED_PLAN | 0 | 0.000000% |

Munich residents: 68770; non-residents: 147655; unresolved persons: 107618.

## Trips made by Munich residents

All main trips made by Munich residents: 137540.

| Spatial category | Trips | Share |
|---|---:|---:|
| BOTH_INSIDE | 123186 | 89.563763% |
| ORIGIN_ONLY | 7177 | 5.218118% |
| DESTINATION_ONLY | 7177 | 5.218118% |
| BOTH_OUTSIDE | 0 | 0.000000% |
| INVALID_OR_MISSING_COORDINATE | 0 | 0.000000% |

Spatial-category completeness check: 137540 = 137540 all resident main trips (`PASS`).

| Current input main mode | Trips | Share |
|---|---:|---:|
| bike | 22192 | 16.134943% |
| car | 48788 | 35.471863% |
| pt | 21914 | 15.932820% |
| walk | 44646 | 32.460375% |

## Closed-subtour readiness

Munich residents with at least one closed subtour: 68770; without a closed subtour: 0. This is a technical readiness diagnostic, not a cohort restriction.

## Unresolved cases and non-intervention

Missing, non-finite or contradictory home information is never classified silently. `unresolved_residents.csv` contains at most 100 lexicographically smallest person IDs with their reason, relevant main activity types and home coordinates. The classification totals above cover every person exactly once.

This step did not filter or write a population and did not change a config, network, schedule, vehicle file, scenario input, calibration constant, behavioral parameter or mode-choice setting.
