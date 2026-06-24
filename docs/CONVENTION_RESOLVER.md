# Convention Resolver

Status: v0.3.0-rc1.8.3 draft

Flow may use conventions during early generation, but hidden defaults must be visible to the user. Defaults such as `branch: main`, `command: mvn test`, `namespace: default`, or `image: <intent>:latest` are reported as assumptions in the Intent Design Report.

This keeps the user in the role of solution architect instead of forcing them to reverse-engineer generated automation behavior.
