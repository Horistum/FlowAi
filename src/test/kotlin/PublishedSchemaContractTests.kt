import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.standard.PublishedSchemaAuthority
import org.flowlang.standard.PublishedSchemaContracts

class PublishedSchemaContractTests {
    @Test
    fun everyPublishedSchemaHasOneExplicitProductionOwnerAndAuthorityClass() {
        val issues = PublishedSchemaContracts.coverageIssues(File("."))
        assertTrue(issues.isEmpty(), issues.joinToString(" | "))
        assertTrue(PublishedSchemaContracts.contracts.all { it.productionOwner.isNotBlank() })
        assertEquals(
            PublishedSchemaContracts.contracts.size,
            PublishedSchemaContracts.contracts.map { it.path }.toSet().size
        )
    }

    @Test
    fun currentPublicSchemasDoNotImpersonateCompleteProductionValidityAuthorities() {
        assertTrue(PublishedSchemaContracts.contracts.isNotEmpty())
        assertTrue(PublishedSchemaContracts.contracts.all {
            it.authority == PublishedSchemaAuthority.SYNTACTIC_INTERCHANGE
        })
    }
}
