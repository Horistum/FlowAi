import org.flowlang.serialization.FlowYaml
import org.flowlang.serialization.FlowYamlException

fun yamlParsingTests() {
    H.eq("yaml/scalar-string", FlowYaml.readMap("a: hello")["a"], "hello")
    H.eq("yaml/quoted", FlowYaml.readMap("a: \"1.0\"")["a"], "1.0")
    H.eq("yaml/bool-true", FlowYaml.readMap("a: true")["a"], true)
    H.eq("yaml/bool-false", FlowYaml.readMap("a: false")["a"], false)
    H.eq("yaml/int", FlowYaml.readMap("a: 42")["a"], 42)
    H.eq("yaml/null", FlowYaml.readMap("a: null")["a"], null)
    run {
        @Suppress("UNCHECKED_CAST")
        val nested = FlowYaml.readMap("a:\n  b:\n    c: 1")["a"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val b = nested["b"] as Map<String, Any?>
        H.eq("yaml/nested", b["c"], 1)
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val list = FlowYaml.readMap("items:\n  - x\n  - y\n  - z")["items"] as List<Any?>
        H.eq("yaml/list", list.size, 3)
        H.eq("yaml/list-first", list[0], "x")
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val flowMap = FlowYaml.readMap("params: { app: demo, replicas: 2 }")["params"] as Map<String, Any?>
        H.eq("yaml/flow-map", flowMap["app"], "demo")
        H.eq("yaml/flow-map-number", flowMap["replicas"], 2)
    }
    run {
        @Suppress("UNCHECKED_CAST")
        val flowList = FlowYaml.readMap("items: [x, y, z]")["items"] as List<Any?>
        H.eq("yaml/flow-list", flowList, listOf("x", "y", "z"))
    }
    run {
        val map = FlowYaml.readMap("a: 1 # comment\n# whole line\nb: 2")
        H.eq("yaml/comment-strip", map["a"], 1)
        H.eq("yaml/comment-kept-key", map["b"], 2)
    }
    run {
        val map = FlowYaml.readMap("a: \"has # hash\"")
        H.eq("yaml/hash-in-quotes", map["a"], "has # hash")
    }
    try {
        FlowYaml.readMap("a:\n\tb: 1", "tabbed-yaml")
        H.ok("yaml/tab-rejected", false)
    } catch (error: FlowYamlException) {
        H.ok("yaml/tab-rejected", error.message?.contains("tabbed-yaml") == true)
    }
}
