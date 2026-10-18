import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.FilePath
import hudson.init.InitMilestone
import hudson.model.BooleanParameterDefinition
import hudson.model.BooleanParameterValue
import hudson.model.ParametersAction
import hudson.model.ParametersDefinitionProperty
import hudson.model.Run
import hudson.model.TaskListener
import hudson.model.listeners.SCMListener
import hudson.scm.SCM
import hudson.scm.SCMRevisionState
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode
import org.jenkinsci.plugins.workflow.graph.BlockEndNode
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Disposable native observer. No scenario names or expected selections enter this runner.
class CompositionCheckoutListener extends SCMListener {
    List observations = Collections.synchronizedList([])

    @Override void onCheckout(Run build, SCM scm, FilePath workspace, TaskListener listener,
                              File changelogFile, SCMRevisionState pollingBaseline) {
        def heads = build.getExecution().getCurrentHeads().findAll {
            it instanceof StepAtomNode && it.getDescriptor()?.getFunctionName() == 'checkout'
        }
        if (heads.size() != 1 || workspace.isRemote()) throw new IllegalStateException('Ambiguous checkout observation')
        def node = heads[0]
        def stage = node.getEnclosingBlocks().find {
            it instanceof StepStartNode && !it.isBody() && it.getDescriptor()?.getFunctionName() == 'stage'
        }
        def stageName = stage == null ? null : ArgumentsAction.getArguments(stage).get('name')
        if (!(stageName instanceof String) || !stageName.startsWith('flow-observe:'))
            throw new IllegalStateException('Checkout has no observation identity')
        def process = new ProcessBuilder('git', '-C', workspace.getRemote(), 'rev-parse', 'HEAD').redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException('Revision probe timed out') }
        def revision = process.inputStream.getText('UTF-8').trim()
        if (process.exitValue() != 0 || !(revision ==~ /[0-9a-f]{40}/)) throw new IllegalStateException('Invalid native revision')
        observations.add([job: build.getParent().getName(), build: build.getNumber(), node: node.getId(),
            task: stageName.substring('flow-observe:'.length()), stage: stage.getId(), revision: revision])
    }
}

Thread.start('composition-observer') {
    def output = new File('/evidence/runtime.json')
    def digest = { bytes -> MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    try {
        def jenkins = Jenkins.get()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (jenkins.getInitLevel() != InitMilestone.COMPLETED) {
            if (System.nanoTime() > deadline) throw new IllegalStateException('Controller initialization timed out')
            Thread.sleep(100)
        }
        jenkins.setNumExecutors(1)
        def observer = new CompositionCheckoutListener()
        jenkins.getExtensionList(SCMListener).add(observer)
        def inventory = new JsonSlurper().parse(new File('/artifacts/runs.json'))
        def results = []
        inventory.each { run ->
            if (!(run.id ==~ /[a-z0-9-]+/) || !(run.artifact ==~ /[a-z0-9-]+\.Jenkinsfile/) ||
                !run.inputs.every { key, value -> key ==~ /[A-Za-z_][A-Za-z0-9_]*/ && value instanceof Boolean })
                throw new IllegalStateException('Invalid declared native run')
            def job = jenkins.createProject(WorkflowJob, run.id)
            def artifact = new File('/artifacts/' + run.artifact).getText('UTF-8')
            job.setDefinition(new CpsFlowDefinition(artifact, true))
            job.addProperty(new ParametersDefinitionProperty(run.inputs.collect { key, value ->
                new BooleanParameterDefinition(key, value, 'Declared conformance input') }))
            def parameters = new ParametersAction(run.inputs.collect { key, value -> new BooleanParameterValue(key, value) })
            def build = job.scheduleBuild2(0, parameters).get(360, TimeUnit.SECONDS)
            if (build.isBuilding() || !build.getExecution().isComplete()) throw new IllegalStateException('Incomplete native run')
            def graph = new FlowGraphWalker(build.getExecution()).toList()
            def checkouts = graph.findAll { it instanceof StepAtomNode && it.getDescriptor()?.getFunctionName() == 'checkout' }
            def events = observer.observations.findAll { it.job == run.id && it.build == build.number }
            def successful = checkouts.findAll { it.getError() == null }
            if (events.collect { it.node }.toSet() != successful.collect { it.id }.toSet() || events.size() != successful.size())
                throw new IllegalStateException('Successful checkout and callback inventories differ')
            def rows = checkouts.collect { node ->
                def stage = node.getEnclosingBlocks().find {
                    it instanceof StepStartNode && !it.isBody() && it.getDescriptor()?.getFunctionName() == 'stage'
                }
                def name = stage == null ? null : ArgumentsAction.getArguments(stage).get('name')
                if (!(name instanceof String) || !name.startsWith('flow-observe:'))
                    throw new IllegalStateException('Native invocation has no observation identity')
                def end = graph.find { it instanceof BlockEndNode && it.getStartNode().getId() == stage.id }
                if (end == null) throw new IllegalStateException('Unclosed observation stage')
                def event = events.find { it.node == node.id }
                [task: name.substring('flow-observe:'.length()), revision: event?.revision, node: node.id,
                 start: Integer.parseInt(stage.id), end: Integer.parseInt(end.id),
                 result: node.getError() == null ? 'success' : 'failure',
                 error: node.getError()?.error?.message]
            }.sort { it.start }
            results.add([id: run.id, result: build.result.toString(), finished: true,
                inputs: run.inputs.collectEntries { key, value -> [(key): build.getAction(ParametersAction).getParameter(key).value] },
                artifactSha256: digest(job.getDefinition().getScript().getBytes('UTF-8')), observations: rows])
            new File('/evidence/' + run.id + '.log').setText(build.getLog(4000).join('\n') + '\n', 'UTF-8')
        }
        output.setText(JsonOutput.toJson([status: 'completed', jenkins: Jenkins.VERSION, java: System.getProperty('java.version'),
            plugins: jenkins.pluginManager.plugins.findAll { it.active }.collectEntries { [(it.shortName): it.version] }.sort(),
            runs: results]) + '\n', 'UTF-8')
        System.exit(0)
    } catch (Throwable failure) {
        output.setText(JsonOutput.toJson([status: 'infrastructure-failure', type: failure.class.name, message: failure.message]) + '\n', 'UTF-8')
        failure.printStackTrace()
        System.exit(1)
    }
}
