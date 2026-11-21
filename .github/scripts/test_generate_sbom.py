import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("generate-sbom.py")
SPEC = importlib.util.spec_from_file_location("generate_sbom", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GenerateSbomTests(unittest.TestCase):
    def test_parses_gradle_tree_and_uses_resolved_version(self):
        report = """debugRuntimeClasspath - Runtime classpath
+--- org.example:core:1.0
|    \\--- org.example:shared:2.0 -> 2.3
\\--- org.example:core:1.0
"""
        components = MODULE.parse_components(report)
        by_name = {item["name"]: item for item in components}
        self.assertEqual("1.0", by_name["core"]["version"])
        self.assertEqual("2.3", by_name["shared"]["version"])
        self.assertEqual("2.0", by_name["shared"]["properties"][0]["value"])

    def test_deduplicates_same_resolved_coordinate(self):
        report = """+--- org.example:core:1.0
\\--- org.example:core:1.0
"""
        self.assertEqual(1, len(MODULE.parse_components(report)))

    def test_rejects_empty_or_unbounded_reports(self):
        self.assertEqual([], MODULE.parse_components("No dependencies"))
        with self.assertRaises(ValueError):
            MODULE.parse_components(
                "\\n".join(
                    f"+--- org.example:lib-{index}:1.0"
                    for index in range(MODULE.MAX_COMPONENTS + 1)
                )
            )


if __name__ == "__main__":
    unittest.main()
