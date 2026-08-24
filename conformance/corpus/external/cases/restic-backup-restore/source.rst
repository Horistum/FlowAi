Create your first backup:

.. code-block:: console

    restic backup ~/work

You can list all the snapshots you created with:

.. code-block:: console

    restic snapshots

You can restore a snapshot by noting the snapshot ID you want and running:

.. code-block:: console

    restic restore --target /tmp/restore-work your-snapshot-ID
