Every time you run a Dag, you are creating a new instance of that Dag which
Airflow calls a :doc:`Dag Run <dag-run>`. Dag Runs can run in parallel for the
same Dag, and each has a defined data interval, which identifies the period of
data the tasks should operate on.
