package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.ai.*
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class RunEvidenceTests {
    private val now = Instant.parse("2026-11-08T08:10:00Z")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")), StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private fun json(value: Any): JsonNode = Json.mapper.valueToTree(value)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun at(second: Long) = now.minusSeconds(100).plusSeconds(second).toString()
    private fun case(capability: StandardCapability = StandardCapability.PROVISION, block: Case.() -> Unit) {
        val root = createTempDirectory("run-evidence-").toFile()
        try { Case(root, capability).block() } finally { root.deleteRecursively() }
    }
    private inner class Case(val root: File, val capability: StandardCapability) {
        val address = ResourceIdentity("tenant-test", "isolated-resource", "recovery-target")
        val parameter = when (capability) { StandardCapability.DATA_SYNC -> "destination"; StandardCapability.SECRET_ROTATE -> "subject"; else -> "resource" }
        val extra = if (capability == StandardCapability.DATA_SYNC) ",\"source\":\"isolated-baseline\"" else ""
        val source = """{"intentVersion":"3.1","kind":"FlowIntentDocument","name":"run-evidence","workflows":[{"name":"main","kind":"CUSTOM","body":[
          {"kind":"operation","id":"approval","capability":"APPROVE"},
          {"kind":"attempt","id":"recover","disposition":"recover","body":[
            {"kind":"retry","id":"retry","maxAttempts":2,"delaySeconds":0,"body":[
              {"kind":"operation","id":"probe","capability":"VERIFY","params":{"resource":${json(address)},"criteria":"healthy"}}]}],
           "onFailure":[{"kind":"operation","id":"repair","capability":"${capability.name}","params":{"$parameter":${json(address)}$extra},"requires":["approval"]},
             {"kind":"operation","id":"verify","capability":"VERIFY","params":{"resource":${json(address)},"criteria":"recovered"}}]}]}]}"""
        var unit = compile(source)
        fun compile(text: String): CompilationUnit = assertIs<CompilationResult.Accepted>(IntentYamlFrontend(compiler).compile(File(root,"source.json").apply { writeText(text) })).unit
        fun select(cap: String, criteria: String? = null) = unit.graph.nodes.single { it.semantics.capability?.value == cap &&
            (criteria == null || it.semantics.parameters["criteria"] == "\"$criteria\"") }.id.value
        val probeSelector = RequirementSelector(StandardCapability.VERIFY, parameters = mapOf("criteria" to "healthy"))
        val verifySelector = RequirementSelector(StandardCapability.VERIFY, parameters = mapOf("criteria" to "recovered"))
        var contract = OperationOutcomeRequirements(2, unit.graphDigest.value, "run-test", "a".repeat(32), at(0), "reviewer",
            listOf(OutcomeResource("resource", address, ResourceDisposition.MANAGED, owner = "team-test")), listOf(
                OperationOutcomeRequirement("probe", probeSelector, "resource", "resource", postconditions = emptyMap(), evidence = AttemptOutcomePolicy(ExpectedOperationStatus.FAILED)),
                OperationOutcomeRequirement("repair", RequirementSelector(capability), "resource", parameter, postconditions = emptyMap(), evidence = AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED)),
                OperationOutcomeRequirement("verify", verifySelector, "resource", "resource", postconditions = mapOf("ready" to json(true)), evidence = AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED))),
            RunEvidenceRequirement("main", ExpectedOperationStatus.SUCCEEDED, listOf(RecoveryEvidenceRequirement("recovery", listOf(probeSelector), listOf(RequirementSelector(capability), verifySelector),
                RequiredFailureDisposition.RECOVER, ExpectedRecoveryPath.RECOVERED, listOf("verify")))))
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val capture = "synthetic observer capture; no external work".toByteArray()
        var trust = OperationObservationTrust(2, listOf(ObservationGrant("observer", b64(key.public.encoded), listOf(address.scope), listOf(address.type), ObservationKind.entries, listOf("main"))))
        fun requirements() = OperationOutcomeReader.requirements(File(root,"requirements.json").apply { writeBytes(Json.mapper.writeValueAsBytes(contract)) })
        fun event(id: String, node: String, attempt: Int, second: Long, status: ObservationStatus = ObservationStatus.SUCCEEDED, cause: String? = null) =
            ObservedInvocation(id, node, attempt, at(second), at(second+1), status, if (status == ObservationStatus.FAILED) "error-$id" else null, cause)
        fun run(): OperationRunObservation = OperationRunObservation(2, "trace", requirements().source.sha256, unit.graphDigest.value, contract.runId, contract.challenge,
            "main", at(0), at(20), true, ObservationStatus.SUCCEEDED, sourceSha256 = OperationOutcomeReader.sha256(capture), sourceSizeBytes = capture.size,
            invocations = listOf(event("approval",unit.graph.nodes.filterIsInstance<CanonicalApprovalNode>().single().id.value,1,1),
                event("probe-1",select("VERIFY","healthy"),1,3,ObservationStatus.FAILED), event("probe-2",select("VERIFY","healthy"),2,5,ObservationStatus.FAILED),
                event("repair",select(capability.name),1,7,cause="probe-2"), event("verify",select("VERIFY","recovered"),1,9,cause="probe-2")))
        fun claims(run: OperationRunObservation): List<OperationObservation> = run.invocations.filter { it.id != "approval" }.flatMap { event ->
            val result = OperationObservation(2, "result-${event.id}", requirements().source.sha256, unit.graphDigest.value, contract.runId, contract.challenge,
                ObservationKind.RESULT, event.status, event.nodeId, address, event.completedAt, OperationOutcomeReader.sha256(capture), capture.size, invocationId=event.id)
            listOf(result) + if (event.id == "repair") listOf(result.copy(id="owner",kind=ObservationKind.OWNERSHIP,status=ObservationStatus.SUCCEEDED,owner="team-test",observedAt=event.startedAt))
            else if (event.id == "verify") listOf(result.copy(id="state",kind=ObservationKind.STATE,status=ObservationStatus.SUCCEEDED,facts=mapOf("ready" to json(true)),observedAt=at(21))) else emptyList()
        }
        fun sign(value: Any, isRun: Boolean): SignedOperationObservation {
            val payload = Json.mapper.writeValueAsBytes(value)
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(if (isRun) OperationObservationAuthentication.runSigningBytes("observer",payload) else OperationObservationAuthentication.signingBytes("observer",payload,2))
            return SignedOperationObservation("observer",b64(payload),b64(signer.sign()),b64(capture))
        }
        fun review(run: OperationRunObservation = run(), claims: List<OperationObservation> = claims(run), mutate: (OperationObservationBundle)->OperationObservationBundle = {it}): OperationOutcomeReview {
            val bundle = mutate(OperationObservationBundle(2,claims.map {sign(it,false)},sign(run,true)))
            val observations = OperationOutcomeReader.observations(File(root,"observations.json").apply {writeBytes(Json.mapper.writeValueAsBytes(bundle))})
            val grants = OperationOutcomeReader.trust(File(root,"trust.json").apply {writeBytes(Json.mapper.writeValueAsBytes(trust))})
            return OperationOutcomeReviewer.evaluate(unit,requirements(),observations,grants,now)
        }
        fun assertCode(review: OperationOutcomeReview, code: String, state: OperationOutcomeState = OperationOutcomeState.UNRESOLVED) {
            assertEquals(state,review.state,review.toString())
            assertTrue(code in review.diagnostics || review.results.any {it.code==code} || review.execution?.code==code || review.execution?.recoveries?.any {it.code==code}==true,review.toString())
        }
    }
    @Test fun recoveryRequiresObservedFinalStateAcrossThreeDomains() {
        for (capability in listOf(StandardCapability.PROVISION,StandardCapability.DATA_SYNC,StandardCapability.SECRET_ROTATE)) case(capability) {
            val result=review(); assertEquals(OperationOutcomeState.SATISFIED,result.state,result.toString())
            assertEquals(ObservationStatus.FAILED,result.results.first().observedStatus)
            assertEquals(5,result.execution!!.invocations.size)
            assertEquals("probe-2",result.execution!!.recoveries.single().observation!!.triggeringInvocationId)
            assertFalse(result.executionAuthorized); assertEquals("unassessed",result.workflowExecutionCorrectness)
            assertCode(review(claims=claims(run()).filter {it.kind!=ObservationKind.STATE}),"OUTCOME_RECOVERY_POSTCONDITION_MISSING")
            assertCode(review(claims=claims(run()).map {if(it.kind==ObservationKind.STATE) it.copy(facts=mapOf("ready" to json(false))) else it}),"OUTCOME_RECOVERY_POSTCONDITION_FAILED",OperationOutcomeState.VIOLATED)
        }
    }
    @Test fun earlierSuccessCannotSurviveRecoveryAsFinalStateEvidence() = case {
        assertCode(review(claims=claims(run()).map {if(it.kind==ObservationKind.STATE) it.copy(observedAt=at(10)) else it}),"OUTCOME_STATE_BEFORE_RUN_COMPLETION")
    }
    @Test fun successfulLastAttemptCannotHideEarlierAttemptResults() = case {
        assertCode(review(claims=claims(run()).filter {it.invocationId!="probe-1"}),"OUTCOME_OBSERVATION_MISSING")
        val run=run(); assertCode(review(run.copy(invocations=run.invocations.filter {it.id!="probe-1"})),"OUTCOME_INVOCATION_PATH")
    }
    @Test fun handlerCannotClaimADifferentFailureOrInventAnAttempt() = case {
        val run=run()
        for(change in listOf<(ObservedInvocation)->ObservedInvocation>({it.copy(causedBy="probe-1")},{it.copy(attempt=2)}))
            assertCode(review(run.copy(invocations=run.invocations.map {if(it.id=="repair") change(it) else it})),"OUTCOME_INVOCATION_PATH")
    }
    @Test fun incompleteUnknownAndTruncatedRunsStayUnresolved() = case {
        val run=run()
        assertCode(review(run.copy(complete=false)),"OUTCOME_RUN_INCOMPLETE")
        assertCode(review(run.copy(invocations=run.invocations.dropLast(1))),"OUTCOME_INVOCATION_MISSING")
        assertCode(review(run.copy(invocations=run.invocations.map {if(it.id=="repair") it.copy(status=ObservationStatus.UNKNOWN) else it})),"OUTCOME_INVOCATION_UNKNOWN")
    }
    @Test fun authenticContradictoryClaimsAndUnboundInvocationsCannotPass() = case {
        val claims=claims(run())
        assertCode(review(claims=claims.map {if(it.id=="result-probe-1") it.copy(status=ObservationStatus.SUCCEEDED) else it}),"OUTCOME_INVOCATION_RESULT")
        assertCode(review(claims=claims+claims.first().copy(id="duplicate")),"OUTCOME_AMBIGUOUS_OBSERVATIONS")
        assertCode(review(claims=claims.map {if(it.id=="result-probe-1") it.copy(invocationId="invented") else it}),"OUTCOME_UNKNOWN_INVOCATION")
        assertCode(review(claims=claims.map {if(it.id=="result-repair") it.copy(invocationId="verify") else it}),"OUTCOME_INVOCATION_NODE")
    }
    @Test fun runSignaturesSourceCaptureAndWorkflowGrantAreMandatory() = case {
        assertCode(review(mutate={it.copy(execution=it.execution!!.copy(signature=b64(ByteArray(64))))}),"OUTCOME_INVALID_SIGNATURE")
        assertCode(review(mutate={it.copy(execution=it.execution!!.copy(source=b64("changed".toByteArray())))}),"OUTCOME_SOURCE_CHANGED")
        assertCode(review(mutate={it.copy(execution=null)}),"OUTCOME_RUN_MISSING")
        trust=trust.copy(grants=trust.grants.map {it.copy(workflows=emptyList())})
        assertCode(review(),"OUTCOME_OBSERVER_SCOPE")
    }
    @Test fun crossRunAndCrossContractReplayFail() = case {
        val run=run()
        for(change in listOf<(OperationRunObservation)->OperationRunObservation>({it.copy(runId="other")},{it.copy(challenge="b".repeat(32))},
            {it.copy(contractSha256="b".repeat(64))},{it.copy(graphDigest="b".repeat(64))},{it.copy(workflow="other")})) {
            val result=review(change(run)); assertEquals(OperationOutcomeState.UNRESOLVED,result.state)
        }
    }
    @Test fun failedRecoveryPreservesBothErrorsAndViolatesExpectedRecovery() = case {
        val run=run(); val events=run.invocations.dropLast(1).map {if(it.id=="repair") it.copy(status=ObservationStatus.FAILED,errorId="repair-error") else it}
        val result=review(run.copy(invocations=events,status=ObservationStatus.FAILED,errorId="repair-error"))
        assertCode(result,"OUTCOME_UNEXPECTED_RECOVERY_PATH",OperationOutcomeState.VIOLATED)
        assertEquals("error-probe-2",result.execution!!.recoveries.single().observation!!.errorId)
        assertEquals("repair-error",result.execution!!.invocations.last().errorId)
    }
    @Test fun handlerCannotBeRunAfterSuccessfulProtectedBody() = case {
        val run=run(); val events=run.invocations.map {if(it.id=="probe-2") it.copy(status=ObservationStatus.SUCCEEDED,errorId=null) else it}
        assertCode(review(run.copy(invocations=events)),"OUTCOME_EXTRA_INVOCATION")
    }
    @Test fun timestampsAndFixedDelayAreCheckedAgainstEveryAttempt() = case {
        val run=run()
        assertCode(review(run.copy(invocations=run.invocations.map {if(it.id=="probe-2") it.copy(startedAt=at(2)) else it})),"OUTCOME_INVOCATION_TIME")
        unit=compile(source.replace("\"delaySeconds\":0","\"delaySeconds\":5")); contract=contract.copy(graphDigest=unit.graphDigest.value)
        assertCode(review(),"OUTCOME_RETRY_DELAY")
    }
    @Test fun ownershipMustPrecedeChangingInvocationNotJustItsResult() = case {
        assertCode(review(claims=claims(run()).map {if(it.kind==ObservationKind.OWNERSHIP) it.copy(observedAt=at(8)) else it}),"OUTCOME_OWNERSHIP_AFTER_START")
    }
    @Test fun resourceSubstitutionAndStaleEvidenceFail() = case {
        contract=contract.copy(resources=contract.resources.map {it.copy(identity=address.copy(key="other"))})
        val result=review(claims=claims(run()).map {it.copy(resource=address.copy(key="other"))})
        assertCode(result,"OUTCOME_RESOURCE_MISMATCH",OperationOutcomeState.VIOLATED)
    }
    @Test fun exactRecoveryMembershipIsIndependentlyChecked() = case {
        contract=contract.copy(execution=contract.execution!!.copy(recoveries=contract.execution!!.recoveries.map {it.copy(handler=it.handler.reversed())}))
        assertCode(review(),"OUTCOME_RECOVERY_STRUCTURE",OperationOutcomeState.VIOLATED)
    }
    @Test fun boundedTraceDoesNotPromoteConditionalControlFlow() = case {
        unit=compile(source.replace("\"body\":[\n          {\"kind\":\"operation\",\"id\":\"approval\"",
            "\"body\":[{\"kind\":\"branch\",\"id\":\"branch\",\"when\":true,\"then\":[{\"kind\":\"value\",\"id\":\"x\",\"value\":true}]},\n          {\"kind\":\"operation\",\"id\":\"approval\""))
        contract=contract.copy(graphDigest=unit.graphDigest.value)
        assertCode(review(),"OUTCOME_UNSUPPORTED_RUN_SHAPE")
    }
    @Test fun runCompletionStatusCannotReplaceCanonicalPropagation() = case {
        unit=compile(source.replace("\"disposition\":\"recover\"","\"disposition\":\"propagate\"")); contract=contract.copy(graphDigest=unit.graphDigest.value)
        assertCode(review(),"OUTCOME_RUN_STATUS_MISMATCH",OperationOutcomeState.VIOLATED)
    }
    @Test fun successfulRetryAndUnreachedHandlerHaveExplicitSeparateResults() = case {
        contract=contract.copy(operations=contract.operations.map {
            when(it.id) {
                "probe" -> it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED))
                else -> it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED,OperationPresence.WHEN_REACHED))
            }
        },execution=contract.execution!!.copy(recoveries=contract.execution!!.recoveries.map {it.copy(expected=ExpectedRecoveryPath.NOT_NEEDED,verificationOperations=emptyList())}))
        val run=run(); val events=run.invocations.take(3).map {if(it.id=="probe-2") it.copy(status=ObservationStatus.SUCCEEDED,errorId=null) else it}
        val result=review(run.copy(invocations=events))
        assertEquals(OperationOutcomeState.SATISFIED,result.state,result.toString())
        assertEquals(listOf("OUTCOME_NOT_REACHED","OUTCOME_NOT_REACHED"),result.results.drop(1).map {it.code})
        assertEquals(ObservationStatus.FAILED,result.execution!!.invocations[1].status)
        assertEquals(ObservationStatus.SUCCEEDED,result.results.first().observedStatus)
    }
    @Test fun propagatedFailureKeepsOriginalErrorAfterSuccessfulHandling() = case {
        unit=compile(source.replace("\"disposition\":\"recover\"","\"disposition\":\"propagate\""))
        contract=contract.copy(graphDigest=unit.graphDigest.value,execution=contract.execution!!.copy(status=ExpectedOperationStatus.FAILED,
            recoveries=contract.execution!!.recoveries.map {it.copy(disposition=RequiredFailureDisposition.PROPAGATE,expected=ExpectedRecoveryPath.PROPAGATED)}))
        val result=review(run().copy(status=ObservationStatus.FAILED,errorId="error-probe-2"))
        assertEquals(OperationOutcomeState.SATISFIED,result.state,result.toString())
        assertEquals(ObservationStatus.FAILED,result.execution!!.status)
        assertEquals(RecoveryPath.PROPAGATED,result.execution!!.recoveries.single().observation!!.path)
        assertCode(review(run().copy(status=ObservationStatus.FAILED,errorId="invented-error")),"OUTCOME_RUN_STATUS_MISMATCH")
    }
    @Test fun unknownVersionsAndLegacyExtensionFieldsAreRejected() = case {
        val file=File(root,"bad.json")
        val valid=json(contract).toString()
        for(bad in listOf(valid.replace("\"formatVersion\":2","\"formatVersion\":99"),valid.replace("\"formatVersion\":2","\"formatVersion\":\"2\""))) {
            file.writeText(bad); assertFailsWith<OutcomeInputException> {OperationOutcomeReader.requirements(file)}
        }
        val legacy=contract.copy(formatVersion=1,execution=null,operations=contract.operations.map {it.copy(evidence=null,postconditions=mapOf("ready" to json(true)))})
        val tree=json(legacy) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.putNull("execution"); file.writeText(tree.toString())
        assertFailsWith<OutcomeInputException> {OperationOutcomeReader.requirements(file)}
    }
    @Test fun missingOwnershipOfFailedMutationIsNotExcusedByItsFailure() = case {
        val run=run(); val events=run.invocations.dropLast(1).map {if(it.id=="repair") it.copy(status=ObservationStatus.FAILED,errorId="repair-error") else it}
        val failed=run.copy(invocations=events,status=ObservationStatus.FAILED,errorId="repair-error")
        val result=review(failed,claims(failed).filter {it.kind!=ObservationKind.OWNERSHIP})
        assertTrue(result.results.any {it.id=="repair" && it.code=="OUTCOME_OBSERVATION_MISSING"},result.toString())
    }
    @Test fun cumulativeClaimFreshnessIncludesFailedAttempts() = case {
        contract=contract.copy(operations=contract.operations.map {it.copy(maxAgeSeconds=95)})
        assertCode(review(),"OUTCOME_OBSERVATION_STALE")
    }
    @Test fun overBudgetTraceAndFalseRecoveryVerifierAreRejected() = case {
        val bad=run().copy(invocations=(1..257).map {event("call-$it",select("VERIFY","healthy"),1,1)})
        assertCode(review(bad,emptyList()),"OUTCOME_INVALID_RUN_OBSERVATION")
        contract=contract.copy(operations=contract.operations.map {if(it.id=="verify") it.copy(postconditions=emptyMap()) else it})
        assertFailsWith<OutcomeInputException> {requirements()}
    }

    @Test fun retryRestartsTheWholeBodyAndRetainsSuccessfulPartialWork() = case {
        val marker="{\"kind\":\"operation\",\"id\":\"probe\""
        val warmup="{\"kind\":\"operation\",\"id\":\"warmup\",\"capability\":\"VERIFY\",\"params\":{\"resource\":${json(address)},\"criteria\":\"warmup\"}},"
        unit=compile(source.replace(marker,warmup+marker))
        val selector=RequirementSelector(StandardCapability.VERIFY,parameters=mapOf("criteria" to "warmup"))
        contract=contract.copy(graphDigest=unit.graphDigest.value,operations=contract.operations+OperationOutcomeRequirement("warmup",selector,"resource","resource",postconditions=emptyMap(),evidence=AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED)),
            execution=contract.execution!!.copy(recoveries=contract.execution!!.recoveries.map {it.copy(protected=listOf(selector)+it.protected)}))
        val run=run(); val events=listOf(run.invocations[0],event("warmup-1",select("VERIFY","warmup"),1,2),run.invocations[1],
            event("warmup-2",select("VERIFY","warmup"),2,4),run.invocations[2])+run.invocations.drop(3)
        val result=review(run.copy(invocations=events))
        assertEquals(OperationOutcomeState.SATISFIED,result.state,result.toString())
        assertEquals(7,result.execution!!.invocations.size)
        assertCode(review(run.copy(invocations=events.filter {it.id!="warmup-2"})),"OUTCOME_INVOCATION_PATH")
    }

    @Test fun oneIndependentContractAcceptsHealthyOrRecoveredRunWithoutPredictingFailure() = case {
        contract=contract.copy(operations=contract.operations.map {
            if(it.id=="probe") it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.COMPLETED))
            else it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED,OperationPresence.WHEN_REACHED))
        },execution=contract.execution!!.copy(recoveries=contract.execution!!.recoveries.map {it.copy(expected=ExpectedRecoveryPath.RECOVER_IF_NEEDED)}))
        val hash=requirements().source.sha256
        assertEquals(OperationOutcomeState.SATISFIED,review().state)
        val run=run(); val healthy=run.copy(invocations=run.invocations.take(2).map {if(it.id=="probe-1") it.copy(status=ObservationStatus.SUCCEEDED,errorId=null) else it})
        val reviewed=review(healthy)
        assertEquals(OperationOutcomeState.SATISFIED,reviewed.state,reviewed.toString())
        assertEquals(RecoveryPath.NOT_NEEDED,reviewed.execution!!.recoveries.single().observation!!.path)
        assertTrue(reviewed.execution!!.recoveries.single().verificationOperationIds.isEmpty())
        assertEquals(hash,requirements().source.sha256)
        assertCode(review(claims=claims(run).filter {it.kind!=ObservationKind.STATE}),"OUTCOME_RECOVERY_POSTCONDITION_MISSING")
    }
    @Test fun conditionalPropagationReportsActualFailureWithoutRequiringItInAdvance() = case {
        unit=compile(source.replace("\"disposition\":\"recover\"","\"disposition\":\"propagate\""))
        contract=contract.copy(graphDigest=unit.graphDigest.value,operations=contract.operations.map {
            if(it.id=="probe") it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.COMPLETED))
            else it.copy(evidence=AttemptOutcomePolicy(ExpectedOperationStatus.SUCCEEDED,OperationPresence.WHEN_REACHED))
        },execution=contract.execution!!.copy(status=ExpectedOperationStatus.COMPLETED,recoveries=contract.execution!!.recoveries.map {
            it.copy(disposition=RequiredFailureDisposition.PROPAGATE,expected=ExpectedRecoveryPath.PROPAGATE_IF_NEEDED)
        }))
        val failed=review(run().copy(status=ObservationStatus.FAILED,errorId="error-probe-2"))
        assertEquals(OperationOutcomeState.SATISFIED,failed.state,failed.toString())
        assertEquals(ObservationStatus.FAILED,failed.execution!!.status)
        val run=run(); val healthy=run.copy(invocations=run.invocations.take(2).map {if(it.id=="probe-1") it.copy(status=ObservationStatus.SUCCEEDED,errorId=null) else it})
        assertEquals(OperationOutcomeState.SATISFIED,review(healthy).state)
    }

}
