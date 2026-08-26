# Resident iteration-0 stuck root-cause diagnosis

This is a read-only technical diagnosis of the preserved 43-hour and 48-hour iteration-0 outputs. Each compressed events file was streamed once. No Controller or QSim was started, and no scenario input or existing output was changed.

## Population-level result

| Runtime cohort | Stuck at 43h and resolved by 48h | Still stuck at 48h |
|---|---:|---:|
| munich_resident | 372 | 818 |
| regional_background | 143 | 687 |
| unresolved_background | 201 | 196 |
| **All** | **716** | **1701** |

716 of the 716 resolved persons have an observed arrival after 43:00 in the 48-hour event stream.

## Evidence-based cause distribution

| Root-cause class | All persons | Munich residents |
|---|---:|---:|
| VERY_LATE_DEPARTURE | 8 | 6 |
| CAR_NO_PROGRESS_OR_NETWORK_CLUSTER | 897 | 546 |
| PT_NEVER_BOARDED | 505 | 212 |
| PT_BOARDED_NOT_ARRIVED | 291 | 54 |
| TELEPORTED_LEG_EXCEEDS_HORIZON | 0 | 0 |
| INSUFFICIENT_EVIDENCE | 0 | 0 |

`VERY_LATE_DEPARTURE` uses a transparent one-hour diagnostic window (departure at or after 47:00); it is not a behavioral assumption. PT classes depend on observed waiting/boarding events. The car class records the last observed vehicle movement and does not by itself claim a routing failure. Unresolved evidence remains explicitly classified as `INSUFFICIENT_EVIDENCE`.

## Concentration and timing evidence

867 of 897 car cases have their final vehicle movement on three links: `419626` (403), `16208` (317), and `453133` (147). All car-class records have `entered link` as the last movement event.

487 of 505 never-boarded cases have an explicit waiting-at-stop event. All 291 boarded-not-arrived cases left a transit vehicle and were waiting for a later connection at the cutoff; none remained aboard. PT stops and the last used transit lines/routes are distributed rather than dominated by one route.

## Interpretation

All persistent records occurred at the 48-hour cutoff, but the cutoff timestamp alone was not used as a causal classification. The five-hour extension allowed 716 persons to arrive, yet it did not resolve the concentrated car queues or the PT waiting chains. The evidence therefore rejects a simple claim that 48 hours alone repairs the technical problem.

The smallest defensible next correction is a targeted network-data audit of the three dominant links and their immediate downstream links, followed by a check that passengers delayed into late transfers still have a service connection. This is a recommendation for a separate controlled correction, not a modification made by this diagnosis. A new protected iteration-0 test is required after any such correction; Run 12 remains blocked.

Detailed person rows retain planned and realized timing, the last event before the stuck event, car movement, and PT stop/vehicle/route evidence. Complete cluster counts are in `stuck_link_or_stop_clusters.csv`.
