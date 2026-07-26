import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.projection.ProjectionBinding

class ProjectionBindingSerializationBoundaryTests {
    @Test
    fun infrastructureMapperOmitsNullProjectionFieldsWithoutCoreAnnotations() {
        val json = Json.mapper.writeValueAsString(ProjectionBinding.literal("value"))

        assertTrue(json.contains("\"kind\""))
        assertTrue(json.contains("\"value\""))
        assertFalse(json.contains("\"name\""))
        assertFalse(json.contains("\"resolutionStatus\""))
    }
}
