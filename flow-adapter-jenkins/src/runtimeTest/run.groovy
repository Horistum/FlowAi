import groovy.json.JsonOutput
import hudson.init.InitMilestone
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Test controller only: no listening host ports, credentials, production jobs or external network.
Thread.start('checkout-certification') {
    def output = new File('/evidence/runtime.json')
    def daemon = null
    def sha256 = { byte[] bytes -> MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    try {
        def scenario = System.getenv('FLOW_CERTIFICATION_SCENARIO') ?: 'jenkins-checkout-runtime'
        def inventories = [
            'jenkins-checkout-runtime': ['baseline', 'omitted-checkout', 'substituted-branch'],
            'jenkins-failure-runtime': ['baseline', 'omitted-failure', 'suppressed-failure'],
            'jenkins-condition-true-runtime': ['baseline', 'flattened-conditions', 'inverted-conditions'],
            'jenkins-condition-false-runtime': ['baseline', 'flattened-conditions', 'inverted-conditions']
        ]
        if (!inventories.containsKey(scenario)) throw new IllegalArgumentException('Unknown certification scenario')
        def jenkins = Jenkins.get()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (jenkins.getInitLevel() != InitMilestone.COMPLETED) {
            if (System.nanoTime() > deadline) throw new IllegalStateException('Controller initialization timed out')
            Thread.sleep(100)
        }
        jenkins.setNumExecutors(1)
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
        def results = []
        inventories[scenario].each { id ->
            def artifact = new File('/artifacts/' + id + '.Jenkinsfile').getText('UTF-8')
            def job = jenkins.createProject(WorkflowJob, id)
            job.setDefinition(new CpsFlowDefinition(artifact, true))
            def build = job.scheduleBuild2(0).get(120, TimeUnit.SECONDS)
            if (build.isBuilding() || !build.getExecution().isComplete()) throw new IllegalStateException('Incomplete pipeline build')
            def workspace = jenkins.getWorkspaceFor(job)
            def marker = workspace.child('marker.txt')
            if (marker.exists() && marker.length() > 1024) throw new IllegalStateException('Oversized workspace observation')
            // Inspect actual executed native steps, including caught errors, without changing pipeline bytes.
            def checkouts = new FlowGraphWalker(build.getExecution()).findAll {
                it instanceof StepAtomNode && it.getDescriptor()?.getFunctionName() == 'git'
            }.sort { Integer.parseInt(it.getId()) }
            def errors = checkouts.findAll { it.getError() != null }.collect {
                def error = it.getError().getError()
                [type: error.getClass().getName(), message: error.getMessage()]
            }
            results.add([id: id, result: build.getResult().toString(), finished: true,
                marker: marker.exists() ? marker.readToString() : null,
                checkoutCount: checkouts.size(), checkoutErrors: errors,
                artifactSha256: sha256(job.getDefinition().getScript().getBytes('UTF-8')),
                buildNumber: build.getNumber()])
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
