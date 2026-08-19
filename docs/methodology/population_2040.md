# Methodology: Munich Population 2040

## Role in the thesis design

The same projected population and five-percent sample are used in BAU and Fast Track. This keeps transport demand constant so that differences are interpreted as modelled infrastructure and service contrasts, not causal forecasts with exact real-world probabilities. Pricing is outside the final thesis scope. Olympic Village and Media Village residents, activities and facilities are not yet represented and remain pending before substantive scenario runs.

## Purpose

This document describes how the 2040 MATSim population is derived from the
existing 5% Munich population. The objective is to provide one identical demand
input for the BAU 2040 and Fast Track 2040 infrastructure scenarios. Keeping the
demand identical allows differences between the scenarios to be attributed to
their infrastructure assumptions.

## Relevant files

### Original and external inputs

- Base population:
  `scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml`
- Population projection:
  `original-input-data/munich-demography/population_projection_2023_2040.csv`
- Munich municipal boundary:
  `original-input-data/munich-demography/munich_boundary.json`
- Source documentation:
  `original-input-data/munich-demography/README.md`

### Preparation classes

- Spatial analysis:
  `src/main/java/org/matsim/project/prepare/AnalyzeMunichPopulation.java`
- Population generation:
  `src/main/java/org/matsim/project/prepare/CreateMunichPopulation2040.java`

### Generated scenario inputs

- `scenarios/munich_bau_2040/population_2040.xml`
- `scenarios/munich_fast_track_2040/population_2040.xml`

The generated XML files are excluded from Git because they are large and can be
reproduced by running `CreateMunichPopulation2040`.

## Spatial classification

The public Munich population contains agents from a wider region. The official
population projection for the City of Munich must therefore not be applied to
all agents.

Each person is classified using the coordinate of the first activity whose type
is `home`:

- If the coordinate lies inside the municipal boundary, the person is treated
  as a Munich resident.
- If the coordinate lies outside the municipal boundary, the person is treated
  as a resident of the surrounding region.
- Persons without a `home` activity are not assigned to either resident group.
- Persons with a `home` activity but without a coordinate are reported
  separately.

The municipal boundary and the MATSim population use DHDN / 3-degree
Gauss-Krueger zone 4 (`EPSG:31468`). Points located exactly on the municipal
boundary are classified as inside Munich.

## Base-population result

The 5% MATSim population contains:

| Classification | Persons |
|---|---:|
| Total population | 324,043 |
| Home inside Munich | 68,770 |
| Home outside Munich | 147,655 |
| Without home activity | 107,618 |
| Without home coordinate | 0 |

For comparison, 5% of the official 2023 population of 1,488,719 would equal
74,436 persons. The MATSim population therefore does not reproduce the official
2023 total exactly. For this reason, relative growth is applied to the existing
MATSim population instead of forcing it to an absolute 5% target.

## Growth factor

The city-wide growth factor is calculated from the population projection:

```text
growth factor = population 2040 / population 2023
              = 1,752,066 / 1,488,719
              = 1.176895
```

This represents population growth of approximately 17.69%.

The target number of Munich residents in the MATSim sample is:

```text
target residents = round(68,770 × 1.176895)
                 = 80,935
```

The number of additional agents is:

```text
additional agents = 80,935 − 68,770
                  = 12,165
```

The final regional MATSim population therefore contains:

```text
324,043 + 12,165 = 336,208 persons
```

## Generation procedure

`CreateMunichPopulation2040` performs the following steps:

1. Read the 2023 and 2040 values from the projection CSV.
2. Read the complete base MATSim population.
3. Read and validate the municipal boundary.
4. Select all persons whose first `home` activity is inside Munich.
5. Draw 12,165 persons randomly from this group with replacement.
6. Deep-copy all plans and person attributes of every selected donor.
7. Assign a new unique ID to every copied person.
8. Add the copied persons to the unchanged original population.
9. Write the BAU 2040 population.
10. Copy the exact same population to the Fast Track 2040 scenario.

A fixed random seed of `2040` is used. This makes the donor selection
reproducible.

Generated IDs follow this pattern:

```text
munich2040_<clone number>_from_<source person ID>
```

## Scenario configuration

Both 2040 configs use the generated local population:

```xml
<param name="inputPlansFile" value="population_2040.xml" />
```

Because the model continues to represent a 5% sample, the capacity factors
remain:

```xml
<param name="flowCapacityFactor" value="0.05" />
<param name="storageCapacityFactor" value="0.05" />
```

## Validation

`AnalyzeMunichPopulation` was run against the generated BAU population:

| Classification | Persons |
|---|---:|
| Total population | 336,208 |
| Home inside Munich | 80,935 |
| Home outside Munich | 147,655 |
| Without home activity | 107,618 |
| Without home coordinate | 0 |

The BAU and Fast Track population files were also compared using SHA-256 and
were byte-identical in the validation run.

## Assumptions and limitations

- Only residents with a `home` coordinate inside Munich are scaled.
- Residents outside Munich remain unchanged because no surrounding-region
  projection is currently applied.
- Persons without a `home` activity remain unchanged.
- The method copies complete daily plans. Consequently, copied agents retain
  the activity coordinates, activity times, modes and other characteristics of
  their source agents.
- Multiple copies may be drawn from the same source person because sampling is
  performed with replacement.
- The copied agents do not represent newly constructed dwellings or a changed
  spatial population distribution. The 2023 spatial distribution is preserved.
- The available source population contains no age attribute. The age groups in
  the projection CSV are therefore summed to calculate the city-wide growth
  factor but are not assigned to individual MATSim agents.
- Age-specific mobility changes cannot currently be represented.
- The approach scales resident demand but does not separately model changing
  commuter, visitor or freight demand.
- BAU 2040 and Fast Track 2040 deliberately use identical initial demand.
- This is a demand-scaling method, not a demographic population synthesis or a
  calibration of the 2023 base scenario.

## Reproduction and checks

In IntelliJ:

1. Run `CreateMunichPopulation2040.main()` to regenerate both 2040 population
   files.
2. Run `AnalyzeMunichPopulation.main()` with this program argument:

   ```text
   scenarios/munich_bau_2040/population_2040.xml
   ```

3. Confirm that `Total persons` equals 336,208 and `Home inside Munich` equals
   80,935.

The line `Expected from 2023 data` in the analysis output is a 2023 reference.
It is not the expected 2040 value.
