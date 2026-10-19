import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.FilePath
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.SCMListener
import hudson.scm.SCM
import hudson.scm.SCMRevisionState
import hudson.init.InitMilestone
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.graph.BlockEndNode
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker
import org.jenkinsci.plugins.workflow.graph.FlowNode
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.support.actions.PauseAction
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Observe physical checkout effects before a later root checkout may replace old branch directories.
// SCM callbacks run outside the synchronous CPS graph listener; no pipeline action is injected.
class ParallelCheckoutEffects extends SCMListener {
    List observations = Collections.synchronizedList([])
    @Override void onCheckout(Run build, SCM scm, FilePath workspace, TaskListener listener,
                              File changelogFile, SCMRevisionState pollingBaseline) {
        def root = Jenkins.get().getWorkspaceFor(build.getParent()).getRemote()
        def path = workspace.getRemote()
        if (workspace.isRemote() || !(path == root || path.startsWith(root + '/')))
            throw new IllegalStateException('Checkout escaped the disposable workspace')
        def marker = workspace.child('marker.txt')
        if (!marker.exists() || marker.length() > 1024) throw new IllegalStateException('Missing or oversized checkout marker')
        def process = new ProcessBuilder('git', '-C', path, 'rev-parse', 'HEAD').redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException('Revision probe timed out') }
        def revision = process.inputStream.getText('UTF-8').trim()
        if (process.exitValue() != 0 || !(revision ==~ /[0-9a-f]{40}/)) throw new IllegalStateException('Invalid native revision')
        observations.add([job: build.getParent().getName(), build: build.getNumber(),
            directory: path == root ? null : path.substring(root.length() + 1), marker: marker.readToString(), revision: revision])
    }
}

// Disposable observer: release inputs by identity; record native facts without deciding equivalence.
Thread.start('parallel-composition-observer') {
    def output = new File('/evidence/runtime.json')
    def daemon = null
    try {
        def jenkins = Jenkins.get()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (jenkins.getInitLevel() != InitMilestone.COMPLETED) {
            if (System.nanoTime() > deadline) throw new IllegalStateException('Controller initialization timed out')
            Thread.sleep(100)
        }
        jenkins.setNumExecutors(1)
        daemon = new ProcessBuilder('git', '-c', 'safe.directory=/fixture/repository.git', 'daemon', '--export-all', '--reuseaddr',
            '--base-path=/fixture', '--listen=127.0.0.1', '--port=9418').redirectErrorStream(true)
            .redirectOutput(new File('/evidence/git-daemon.log')).start()
        boolean available = false
        for (int attempt = 0; attempt < 30 && !available; attempt++) {
            def probe = new ProcessBuilder('git', 'ls-remote', 'git://127.0.0.1:9418/repository.git').redirectErrorStream(true).start()
            if (!probe.waitFor(2, TimeUnit.SECONDS)) probe.destroyForcibly()
            else available = probe.exitValue() == 0 && probe.inputStream.getText('UTF-8').contains('refs/heads/alternate')
            if (!available) Thread.sleep(100)
        }
        if (!available) throw new IllegalStateException('Fixture repository unavailable')
        def digest = { bytes -> MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
        def stageOf = { node -> node.getEnclosingBlocks().find {
            it instanceof StepStartNode && !it.isBody() && it.getDescriptor()?.getFunctionName() == 'stage' &&
                ArgumentsAction.getArguments(it).get('name')?.startsWith('flow-observe:')
        } }
        def identity = { node ->
            def stage = stageOf(node)
            stage == null ? null : ArgumentsAction.getArguments(stage).get('name').substring('flow-observe:'.length())
        }
        def effects = new ParallelCheckoutEffects()
        jenkins.getExtensionList(SCMListener).add(effects)
        def results = []
        new JsonSlurper().parse(new File('/artifacts/runs.json')).each { run ->
            if (!(run.id ==~ /[a-z0-9-]+/) || !(run.artifact ==~ /[a-z0-9-]+\.Jenkinsfile/) ||
                !run.release.every { it ==~ /[a-zA-Z0-9_]+/ } || !run.directories.every { it == '.' || it ==~ /\.flow-parallel\/[a-z0-9_-]+/ })
                throw new IllegalStateException('Invalid run protocol')
            def artifact = new File('/artifacts/' + run.artifact).getText('UTF-8')
            def job = jenkins.createProject(WorkflowJob, run.id)
            job.setDefinition(new CpsFlowDefinition(artifact, true))
            def future = job.scheduleBuild2(0)
            def build = future.getStartCondition().get(60, TimeUnit.SECONDS)
            def pending = {
                def action = build.getAction(InputAction)
                (action == null ? [] : action.getExecutions().findAll { !it.isSettled() })
                    .findAll { PauseAction.isPaused(it.getContext().get(FlowNode)) }
            }
            // A bounded window exposes serialized mutants; timeout is an observation, not a verdict.
            long overlap = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (!future.isDone() && pending().size() < run.initialCount && System.nanoTime() < overlap) Thread.sleep(100)
            def initial = pending().collect { identity(it.getContext().get(FlowNode)) }.sort()
            def decisions = []
            run.release.each { id ->
                long waiting = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                def input = null
                while (!future.isDone() && input == null && System.nanoTime() < waiting) {
                    input = pending().find { identity(it.getContext().get(FlowNode)) == id }
                    if (input == null) Thread.sleep(100)
                }
                if (input != null) {
                    decisions.add([id: id, action: id == run.reject ? 'reject' : 'approve'])
                    if (id == run.reject) input.doAbort() else input.doProceedEmpty()
                }
            }
            // Drain unexpected gates so a semantic mutant produces reviewable evidence rather than a hung job.
            long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!future.isDone() && System.nanoTime() < drain) {
                pending().each { input ->
                    decisions.add([id: identity(input.getContext().get(FlowNode)), action: 'drain'])
                    input.doProceedEmpty()
                }
                Thread.sleep(100)
            }
            build = future.get(30, TimeUnit.SECONDS)
            def graph = new FlowGraphWalker(build.getExecution()).toList()
            def endOf = { node -> graph.find { it instanceof BlockEndNode && it.getStartNode().id == node.id } }
            def rows = graph.findAll { it instanceof StepAtomNode && it.getDescriptor()?.getFunctionName() in ['input', 'git'] }.collect { node ->
                def stage = stageOf(node); def end = stage == null ? null : endOf(stage)
                if (stage == null || end == null) throw new IllegalStateException('Missing observation envelope')
                def branch = node.getEnclosingBlocks().find { it.getAction(ThreadNameAction) != null }
                def dir = node.getEnclosingBlocks().find {
                    it instanceof StepStartNode && !it.isBody() && it.getDescriptor()?.getFunctionName() == 'dir'
                }
                def error = node.getError()?.getError()
                def args = ArgumentsAction.getArguments(node)
                [id: identity(node), node: node.id, kind: node.getDescriptor().getFunctionName() == 'input' ? 'approval' : 'task',
                 start: Integer.parseInt(stage.id), end: Integer.parseInt(end.id),
                 branch: branch?.getAction(ThreadNameAction)?.getThreadName(), directory: dir == null ? null : ArgumentsAction.getArguments(dir).get('path'),
                 gitBranch: args.get('branch'), message: args.get('message'), result: error == null ? 'success' : 'failure',
                 errorType: error?.getClass()?.getName(), errorMessage: error?.getMessage(),
                 causes: error instanceof FlowInterruptedException ? error.getCauses().collect { it.getClass().getName() } : []]
            }.sort { it.start }
            def groups = graph.findAll { it instanceof StepStartNode && !it.isBody() && it.getDescriptor()?.getFunctionName() == 'parallel' }.collect { group ->
                def branches = graph.findAll { it.getAction(ThreadNameAction) != null && it.getEnclosingBlocks().any { it.id == group.id } }.collect { branch ->
                    [id: branch.getAction(ThreadNameAction).getThreadName(), start: Integer.parseInt(branch.id), end: Integer.parseInt(endOf(branch).id)]
                }.sort { it.start }
                [start: Integer.parseInt(group.id), end: Integer.parseInt(endOf(group).id), branches: branches]
            }.sort { it.start }
            def workspace = jenkins.getWorkspaceFor(job)
            def markers = run.directories.collectEntries { path ->
                def marker = workspace.child(path + '/marker.txt')
                if (marker.exists() && marker.length() > 1024) throw new IllegalStateException('Oversized marker')
                [(path): marker.exists() ? marker.readToString() : null]
            }
            def terminal = build.getExecution().getCauseOfFailure()
            def origin = terminal == null ? null : ErrorAction.findOrigin(terminal, build.getExecution())
            results.add([id: run.id, finished: !build.isBuilding() && build.getExecution().isComplete(),
                artifactSha256: digest(job.getDefinition().getScript().getBytes('UTF-8')), result: build.result.toString(),
                initialPending: initial, decisions: decisions, leaves: rows, groups: groups, markers: markers,
                checkoutEffects: effects.observations.findAll { it.job == run.id && it.build == build.number },
                terminalOrigin: origin == null ? null : identity(origin)])
            new File('/evidence/' + run.id + '.log').setText(build.getLog(4000).join('\n') + '\n', 'UTF-8')
        }
        output.setText(JsonOutput.toJson([status: 'completed', jenkins: Jenkins.VERSION, java: System.getProperty('java.version'),
            plugins: jenkins.pluginManager.plugins.findAll { it.active }.collectEntries { [(it.shortName): it.version] }.sort(), runs: results]) + '\n', 'UTF-8')
        System.exit(0)
    } catch (Throwable failure) {
        output.setText(JsonOutput.toJson([status: 'infrastructure-failure', type: failure.class.name, message: failure.message]) + '\n', 'UTF-8')
        failure.printStackTrace()
        System.exit(1)
    } finally { daemon?.destroyForcibly() }
}
