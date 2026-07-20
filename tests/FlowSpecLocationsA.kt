import org.flowlang.ast.BinaryExpressionNode
import org.flowlang.ast.LogicalExpressionNode
import org.flowlang.ast.ReferenceNode

fun expressionSourceLocationTests() {
    val reference = expr("a.b") as ReferenceNode
    H.ok("loc/ref-present", reference.location != null)
    H.eq("loc/ref-line", reference.location?.line, 1)
    H.ok("loc/ref-col", (reference.location?.column ?: 0) >= 1)
    H.ok("loc/binary-present", (expr("a == b") as BinaryExpressionNode).location != null)
    H.ok("loc/logical-present", (expr("a and b") as LogicalExpressionNode).location != null)
}
