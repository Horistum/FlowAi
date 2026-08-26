example_asset = Asset("s3://asset/example.csv")
with DAG(dag_id="producer", ...):
    BashOperator(task_id="producer", outlets=[example_asset], ...)
