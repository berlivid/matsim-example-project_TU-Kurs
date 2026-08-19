# Original and derived input data

This directory separates external source data, versioned processing decisions and reproducible generated artifacts from final MATSim scenario inputs.

- `mvv_gtfs_2037/raw/` contains unchanged forecast GTFS source files and is never edited by the processing tools.
- `mvv_gtfs_2037/common_service_specification.csv`, `fast_track_service_specification.csv` and `fast_track_stop_decisions.csv` are version-controlled modelling decisions.
- `mvv_gtfs_2037/generated/` contains reproducible GTFS ZIP files and preflight reports; large generated artifacts are Git-ignored.
- `munich-demography/` documents inputs used to derive the common 2040 population.

See [the GTFS source README](mvv_gtfs_2037/README.md), [the public-transport methodology](../docs/gtfs2040/gtfs2037_fast_track_method.md) and [the population methodology](../docs/methodology/population_2040.md). Source data retain their providers’ licences and must not be redistributed without checking the applicable terms.
