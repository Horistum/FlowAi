StackStorm helps automate common operational patterns. Some examples are:

* **Facilitated Troubleshooting** - triggering on system failures captured by Nagios, Sensu, New Relic and other monitoring, running a series of diagnostic checks on physical nodes, OpenStack or Amazon instances, and application components, and posting results to a shared communication context, like Slack or JIRA.
* **Automated remediation** - identifying and verifying hardware failure on OpenStack compute node, properly evacuating instances and emailing VM about potential downtime, but if anything goes wrong - freezing the workflow and calling PagerDuty to wake up a human.
* **Continuous deployment** - build and test with Jenkins, provision a new AWS cluster, turn on some traffic with the load balancer, and roll-forth or roll-back based on NewRelic app performance data.

StackStorm helps you compose these and other operational patterns as rules and workflows or actions; and these rules and workflows - the content within the StackStorm platform - are stored *as code* which means they support the same approach to collaboration that you use today for code development and can be shared with the broader open source community via [StackStorm Exchange](https://exchange.stackstorm.org).

### Who is using StackStorm?

See the list of known StackStorm [ADOPTERS.md](/ADOPTERS.md) and [Thought Leaders](https://stackstorm.com/stackstorm-thought-leaders/).

### How it works

#### StackStorm architecture

![StackStorm architecture diagram](https://user-images.githubusercontent.com/597113/92291633-6b5aae00-eece-11ea-912e-3bf977aa3cea.png)

StackStorm plugs into the environment via an extensible set of adapters: sensors and actions.

* **Sensors** are Python plugins for inbound integration that watch for events from external systems and fire a StackStorm trigger when an event happens.

* **Triggers** are StackStorm representations of external events. There are generic triggers (e.g., timers, webhooks) and integration triggers (e.g., Sensu alert, JIRA issue updated). A new trigger type can be defined by writing a sensor plugin.

* **Actions** are StackStorm outbound integrations. There are generic actions (SSH, HTTP request), integrations (OpenStack, Docker, Puppet), or custom actions. Actions are either Python plugins, or any scripts, consumed into StackStorm by adding a few lines of metadata. Actions can be invoked directly by user via CLI, API, or the web UI, or used and called as part of automations - rules and workflows.

* **Rules** map triggers to actions (or to workflows), applying matching criterias and map trigger payload data to action inputs.

* **Workflows** stitch actions together into "uber-actions", defining the order, transition conditions, and passing context data from one action to the next. Most automations are multi-step (eg: more than one action). Workflows, just like "atomic" actions, are available in the action library, and can be invoked manually or triggered by rules.