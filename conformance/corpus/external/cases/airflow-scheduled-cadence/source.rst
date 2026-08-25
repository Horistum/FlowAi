Dags do not *require* a schedule, but it's very common to define one. You define it via the ``schedule`` argument, like this::
    with DAG("my_daily_dag", schedule="@daily"):
