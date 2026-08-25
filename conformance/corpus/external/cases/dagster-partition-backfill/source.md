Backfilling is the process of running partitions for assets that either don't exist or updating existing records. Dagster supports backfills for each partition or a subset of partitions.
After defining a [partition](/guides/build/partitions-and-backfills/partitioning-assets), you can launch a backfill that will submit runs to fill in multiple partitions at the same time.
