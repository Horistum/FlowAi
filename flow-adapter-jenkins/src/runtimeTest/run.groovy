import groovy.json.JsonOutput
import hudson.init.InitMilestone
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction
import org.jenkinsci.plugins.workflow.support.actions.PauseAction
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Test controller only: no published host ports, credentials or production jobs.
// Only the shared immutable checkout scenario gets public Git egress; legacy fixtures stay offline.
Thread.start('checkout-certification') {
    def output = new File('/evidence/runtime.json')
    def daemon = null
    def sha256 = { byte[] bytes -> MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    try {
        def scenario = System.getenv('FLOW_CERTIFICATION_SCENARIO') ?: 'jenkins-checkout-runtime'
        def inventories = [
            'jenkins-shared-checkout-runtime': ['baseline', 'omitted-checkout', 'substituted-revision'],
            'jenkins-checkout-runtime': ['baseline', 'omitted-checkout', 'substituted-branch'],
            'jenkins-failure-runtime': ['baseline', 'omitted-failure', 'suppressed-failure'],
            'jenkins-condition-true-runtime': ['baseline', 'flattened-conditions', 'inverted-conditions'],
            'jenkins-condition-false-runtime': ['baseline', 'flattened-conditions', 'inverted-conditions'],
            'jenkins-error-failure-runtime': ['baseline', 'omitted-handler', 'suppressed-propagation'],
            'jenkins-error-success-runtime': ['baseline', 'unconditional-handler', 'omitted-body'],
            'jenkins-recovery-failure-runtime': ['baseline', 'omitted-handler', 'rethrown-failure', 'omitted-continuation'],
            'jenkins-recovery-success-runtime': ['baseline', 'unconditional-handler', 'omitted-continuation'],
            'jenkins-approval-approve-runtime': ['baseline', 'omitted-approval', 'late-approval'],
            'jenkins-approval-reject-runtime': ['baseline', 'omitted-approval', 'late-approval', 'suppressed-rejection']
        ]
        if (!inventories.containsKey(scenario)) throw new IllegalArgumentException('Unknown certification scenario')
        def sharedCheckout = scenario == 'jenkins-shared-checkout-runtime'
        def jenkins = Jenkins.get()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (jenkins.getInitLevel() != InitMilestone.COMPLETED) {
            if (System.nanoTime() > deadline) throw new IllegalStateException('Controller initialization timed out')
            Thread.sleep(100)
        }
        jenkins.setNumExecutors(1)
        if (!sharedCheckout) {
            daemon = new ProcessBuilder('git', '-c', 'safe.directory=/fixture/repository.git', 'daemon', '--export-all', '--reuseaddr',
                '--base-path=/fixture', '--listen=127.0.0.1', '--port=9418')
                .redirectErrorStream(true).redirectOutput(new File('/evidence/git-daemon.log')).start()
            // Establish repository availability separately from the behavior under test.
            boolean available = false
            for (int attempt = 0; attempt < 30 && !available; attempt++) {
                def probe = new ProcessBuilder('git', 'ls-remote', 'git://127.0.0.1:9418/repository.git')
                    .redirectErrorStream(true).redirectOutput(new File('/evidence/git-probe.log')).start()
                if (!probe.waitFor(2, TimeUnit.SECONDS)) probe.destroyForcibly()
                else {
                    def refs = new File('/evidence/git-probe.log').getText('UTF-8').readLines()
                    available = probe.exitValue() == 0 &&
                        refs.any { it.endsWith('\trefs/heads/selected') } &&
                        refs.any { it.endsWith('\trefs/heads/alternate') } &&
                        !refs.any { it.endsWith('\trefs/heads/missing-revision') }
                }
                if (!available) Thread.sleep(200)
            }
            if (!available) throw new IllegalStateException('Fixture Git server is unavailable')
        }
        def results = []
        def nativeSteps = { build, name ->
            new FlowGraphWalker(build.getExecution()).findAll {
                it instanceof StepAtomNode && it.getDescriptor()?.getFunctionName() == name
            }.sort { Integer.parseInt(it.getId()) }
        }
        def readMarker = { workspace ->
            def file = workspace.child('marker.txt')
            if (file.exists() && file.length() > 1024) throw new IllegalStateException('Oversized workspace observation')
            file.exists() ? file.readToString() : null
        }
        def inputFailure = { error ->
            [type: error.getClass().getName(),
             result: error instanceof FlowInterruptedException ? error.getResult().toString() : null,
             causes: error instanceof FlowInterruptedException ? error.getCauses().collect { it.getClass().getName() } : []]
        }
        inventories[scenario].each { id ->
            def artifact = new File('/artifacts/' + id + '.Jenkinsfile').getText('UTF-8')
            def job = jenkins.createProject(WorkflowJob, id)
            job.setDefinition(new CpsFlowDefinition(artifact, true))
            def future = job.scheduleBuild2(0)
            def pending = null
            def decision = 'none'
            if (scenario.startsWith('jenkins-approval-')) {
                def started = future.getStartCondition().get(60, TimeUnit.SECONDS)
                long inputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (!future.isDone()) {
                    if (System.nanoTime() > inputDeadline) throw new IllegalStateException('Input observation timed out')
                    def action = started.getAction(InputAction)
                    def inputs = action == null ? [] : action.getExecutions().findAll { !it.isSettled() }
                    if (!inputs.isEmpty()) {
                        if (inputs.size() != 1) throw new IllegalStateException('Unexpected pending input inventory')
                        def input = inputs[0]
                        def inputNodes = nativeSteps(started, 'input')
                        if (inputNodes.size() != 1 || !PauseAction.isPaused(inputNodes[0])) {
                            Thread.sleep(100)
                            continue
                        }
                        // Observe the actual pending input before submitting a test decision.
                        // The generated pipeline is never instrumented or modified by the observer.
                        pending = [message: input.getInput().getMessage(), activeInputs: inputs.size(), paused: true,
                            checkoutCount: nativeSteps(started, 'git').size(), marker: readMarker(jenkins.getWorkspaceFor(job)),
                            building: started.isBuilding(), complete: started.getExecution().isComplete()]
                        if (!pending.building || pending.complete) throw new IllegalStateException('Input is not pending')
                        decision = scenario == 'jenkins-approval-approve-runtime' ? 'approve' : 'reject'
                        if (decision == 'approve') input.doProceedEmpty()
                        else input.doAbort()
                        break
                    }
                    Thread.sleep(100)
                }
            }
            def build = future.get(120, TimeUnit.SECONDS)
            if (build.isBuilding() || !build.getExecution().isComplete()) throw new IllegalStateException('Incomplete pipeline build')
            def workspace = jenkins.getWorkspaceFor(job)
            def marker = workspace.child('marker.txt')
            if (marker.exists() && marker.length() > 1024) throw new IllegalStateException('Oversized workspace observation')
            // Inspect actual executed native steps, including caught errors, without changing pipeline bytes.
            def checkouts = nativeSteps(build, sharedCheckout ? 'checkout' : 'git')
            def errors = checkouts.findAll { it.getError() != null }.collect {
                def error = it.getError().getError()
                [type: error.getClass().getName(), message: error.getMessage()]
            }
            def record = [id: id, result: build.getResult().toString(), finished: true,
                marker: marker.exists() ? marker.readToString() : null,
                checkoutCount: checkouts.size(), checkoutErrors: errors,
                artifactSha256: sha256(job.getDefinition().getScript().getBytes('UTF-8')),
                buildNumber: build.getNumber()]
            if (sharedCheckout) {
                // Observe the completed workspace independently; the pipeline bytes are unchanged.
                if (workspace.isRemote()) throw new IllegalStateException('Expected the disposable controller workspace')
                def repository = workspace.child('.git')
                def observed = workspace.child('.flow-agent/work-packages/behavioral-adapter-certification.yaml')
                if (!repository.exists()) {
                    if (workspace.exists() && !workspace.list().isEmpty()) throw new IllegalStateException('Omitted checkout workspace is not empty')
                    record.revision = null
                    record.markerSha256 = null
                } else {
                    if (!repository.isDirectory() || !observed.exists() || observed.length() < 1 || observed.length() > 65536)
                        throw new IllegalStateException('Missing or oversized shared fixture observation')
                    def probeLog = new File('/evidence/' + id + '-revision.log')
                    def probe = new ProcessBuilder('git', '-C', workspace.getRemote(), 'rev-parse', 'HEAD')
                        .redirectErrorStream(true).redirectOutput(probeLog).start()
                    if (!probe.waitFor(30, TimeUnit.SECONDS)) {
                        probe.destroyForcibly()
                        throw new IllegalStateException('Git revision observation timed out')
                    }
                    def revision = probeLog.getText('UTF-8').trim()
                    if (probe.exitValue() != 0 || !(revision ==~ /[0-9a-f]{40}/)) throw new IllegalStateException('Invalid Git revision observation')
                    record.revision = revision
                    record.markerSha256 = observed.read().withCloseable { input -> sha256(input.readAllBytes()) }
                }
            }
            if (scenario.startsWith('jenkins-approval-')) {
                def inputs = nativeSteps(build, 'input')
                def terminal = build.getExecution().getCauseOfFailure()
                def origin = terminal == null ? null : ErrorAction.findOrigin(terminal, build.getExecution())
                def inputIndex = origin == null ? -1 : inputs.findIndexOf { it.getId() == origin.getId() }
                record.approval = [inputCount: inputs.size(),
                    inputErrors: inputs.findAll { it.getError() != null }.collect { inputFailure(it.getError().getError()) },
                    pending: pending, decision: decision,
                    terminalError: terminal == null ? null : inputFailure(terminal) + [inputIndex: inputIndex < 0 ? null : inputIndex + 1]]
            }
            if (scenario in ['jenkins-recovery-failure-runtime', 'jenkins-recovery-success-runtime']) {
                record.checkoutErrorIndices = checkouts.withIndex().findAll { node, index -> node.getError() != null }
                    .collect { node, index -> index + 1 }
            }
            if (scenario in ['jenkins-error-failure-runtime', 'jenkins-error-success-runtime',
                             'jenkins-recovery-failure-runtime', 'jenkins-recovery-success-runtime']) {
                def terminal = build.getExecution().getCauseOfFailure()
                def origin = terminal == null ? null : ErrorAction.findOrigin(terminal, build.getExecution())
                def checkoutIndex = origin == null ? -1 : checkouts.findIndexOf { it.getId() == origin.getId() }
                record.terminalError = terminal == null ? null : [type: terminal.getClass().getName(),
                    message: terminal.getMessage(), checkoutIndex: checkoutIndex < 0 ? null : checkoutIndex + 1]
            }
            results.add(record)
            new File('/evidence/' + id + '.log').setText(build.getLog(2000).join('\n') + '\n', 'UTF-8')
        }
        def plugins = jenkins.pluginManager.plugins.findAll { it.isActive() }
            .collectEntries { [(it.shortName): it.version] }.sort()
        output.setText(JsonOutput.toJson([status: 'completed', jenkins: Jenkins.VERSION,
            java: System.getProperty('java.version'), plugins: plugins, runs: results]) + '\n', 'UTF-8')
        System.exit(0)
    } catch (Throwable failure) {
        output.setText(JsonOutput.toJson([status: 'infrastructure-failure',
            type: failure.class.name, message: failure.message]) + '\n', 'UTF-8')
        failure.printStackTrace()
        System.exit(1)
    } finally {
        if (daemon != null) daemon.destroyForcibly()
    }
}
