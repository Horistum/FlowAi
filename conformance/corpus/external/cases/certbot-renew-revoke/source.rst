.. code-block:: shell

  certbot renew --key-type ecdsa --cert-name example.com --force-renewal

If you want to use ECDSA keys for all certificates in the future (including renewals
of existing certificates), you can add the following line to Certbot's
:ref:`configuration file <config-file>`:

.. code-block:: ini

  key-type = ecdsa

which will take effect upon the next renewal of each certificate.

Revoking certificates
---------------------

If you need to revoke a certificate, use the ``revoke`` subcommand to do so.

A certificate may be revoked by providing its name (see ``certbot certificates``) or by providing
its path directly::

  certbot revoke --cert-name example.com

  certbot revoke --cert-path /etc/letsencrypt/live/example.com/cert.pem

If the certificate being revoked was obtained via the ``--staging``, ``--test-cert`` or a non-default ``--server`` flag,
that flag must be passed to the ``revoke`` subcommand.

.. note:: After revocation, Certbot will (by default) ask whether you want to **delete** the certificate.
          Unless deleted, Certbot will try to renew revoked certificates the next time ``certbot renew`` runs.

You can also specify the reason for revoking your certificate by using the ``reason`` flag.
Reasons include ``unspecified`` which is the default, as well as ``keycompromise``,
``affiliationchanged``, ``superseded``, and ``cessationofoperation``::

  certbot revoke --cert-name example.com --reason keycompromise

Revoking by account key or certificate private key
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

By default, Certbot will try revoke the certificate using your ACME account key. If the certificate was created from
the same ACME account, the revocation will be successful.

If you instead have the corresponding private key file to the certificate you wish to revoke, use ``--key-path`` to perform the
revocation from any ACME account::