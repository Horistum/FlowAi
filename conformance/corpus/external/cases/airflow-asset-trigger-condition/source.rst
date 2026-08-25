Because the ``schedule`` parameter is a list, Dags can require multiple assets. Airflow schedules a Dag after **all** assets
the Dag consumes have been updated at least once since the last time the Dag ran:
