"""Tests the entity-query gate in gradle/entity-query-gate.gradle.kts: an entity query (getEntitiesOfClass, getEntities, getNearbyEntities,
getNearestEntity, getEntityGetter, getAllEntities, or getEntity with an id) in production code fails the build unless a `// entity-query: <reason>` marker with a
reason sits on its line or the one above.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py entity_query
"""
import unittest

import gate_batch
from gate_batch import Case

# Clean files: each must be left alone by the gate.
CLEAN = {
    "MarkedAboveFixture.java": """
public class MarkedAboveFixture {
    boolean near(ServerLevel level, AABB box) {
        // entity-query: the box is around the acting player
        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();
    }
}
""",
    "MarkedSameLineFixture.java": """
public class MarkedSameLineFixture {
    boolean near(ServerLevel level, AABB box) {
        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty(); // entity-query: around the acting player
    }
}
""",
    "MentionsFixture.java": """
/** Reads the pods with {@code level.getEntitiesOfClass(PodEntity.class, box)}, which it must not. */
public class MentionsFixture {
    /*
     * level.getEntities(type, predicate) in a block comment is not a query
     */
    // level.getNearbyEntities(box) in a comment is not a query either
    void read(ServerLevel level, DamageSource source) {
        String text = "level.getEntitiesOfClass(PodEntity.class, box) and level.getEntity(id)";
        Entity cause = source.getEntity();
        List<ServerPlayer> players = level.players();
        Function<DamageSource, String> name = DamageSource::getMsgId;
    }
}
""",
}
# label: (statement, line the gate reports)
QUERIES = {
    "getEntitiesOfClass": ("return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();", 4),
    "getEntities": ("return level.getEntities(EntityTypeTest.forClass(PodEntity.class), pod -> true).isEmpty();", 4),
    "getEntities null": ("return level.getEntities((Entity) null, box, entity -> true).isEmpty();", 4),
    "getNearbyEntities": ("return level.getNearbyEntities(PodEntity.class, TargetingConditions.DEFAULT, null, box).isEmpty();", 4),
    "getNearestEntity": ("return level.getNearestEntity(PodEntity.class, TargetingConditions.DEFAULT, null, 0, 0, 0, box) == null;", 4),
    "getEntity with its argument on the next line": ("return level.getEntity(\n            id) == null;", 4),
    "getEntityGetter": ("return level.getEntityGetter().getEntities(null, box).isEmpty();", 4),
    "getAllEntities": ("return level.getAllEntities().iterator().hasNext();", 4),
    "getEntity by id": ("return level.getEntity(id) == null;", 4),
    "getEntity method reference": ("return ids.stream().map(level::getEntity).anyMatch(Objects::nonNull);", 4),
    "getEntitiesOfClass method reference": ("return boxes.stream().map(this::getEntitiesOfClass).anyMatch(List::isEmpty);", 4),
    "getEntity by id on a second line": ("return level\n            .getEntity (id) == null;", 5),
}
TWO_ABOVE = "// entity-query: too far above\n        int gap = 0;\n        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();"
BARE = "// entity-query:\n        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();"
IN_STRING = 'String note = "// entity-query: not a comment"; return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();'
IN_TEXT_BLOCK = 'String note = """\n            // entity-query: in a text block\n            """; return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();'

# palantir-java-format moves a marker within a wrapped statement; the shared markedNear must still not reach a marker that belongs elsewhere.
NEXT_STATEMENT = "boolean empty = level.getEntitiesOfClass(PodEntity.class, box).isEmpty();\n        int gap = 0; // entity-query: belongs to the next statement\n        return empty;"
BLANK_LINE = "// entity-query: separated by a blank line\n\n        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();"
URL_LINE = '// entity-query: above a line whose string holds //\n        String url = "http://x";\n        return level.getEntitiesOfClass(PodEntity.class, box).isEmpty();'
FAR_END = "return level.getEntitiesOfClass(PodEntity.class,\n" + ",\n".join(f"            arg{i}" for i in range(14)) + ",\n            box).isEmpty(); // entity-query: more than 12 lines away"

MARKERS = {
    "a bare marker": (BARE, 5),
    "a marker on the next statement": (NEXT_STATEMENT, 4),
    "a marker above a line with // in a string": (URL_LINE, 6),
    "a marker above a blank line": (BLANK_LINE, 6),
    "a marker more than 12 lines down a wrapped statement": (FAR_END, 4),
    "two lines above": (TWO_ABOVE, 6),
    "a marker in a string": (IN_STRING, 4),
    "a marker in a text block": (IN_TEXT_BLOCK, 6),
}
BAD = {**{f"query/{label}": case for label, case in QUERIES.items()}, **{f"marker/{label}": case for label, case in MARKERS.items()}}


def asking(statement: str) -> str:
    return f"""
public class AsksFixture {{
    boolean near(ServerLevel level, AABB box, UUID id) {{
        {statement}
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {**CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None, gate="entity_query"),
    **{label: Case(fixture({"AsksFixture.java": asking(statement)}), None, gate="entity_query") for label, (statement, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class EntityQueryGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        _, line = BAD[label]
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"AsksFixture.java:{line} asks an entity query", result.message)
        self.assertIn("// entity-query: <reason>", result.message)
        for exempt in ("MarkedAboveFixture", "MarkedSameLineFixture", "MentionsFixture"):
            self.assertNotIn(exempt, result.message)

    def test_marked_and_mentioned_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_unmarked_entity_query_fails(self):
        for label in QUERIES:
            with self.subTest(label):
                self.check_fails(f"query/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in MARKERS:
            with self.subTest(label):
                self.check_fails(f"marker/{label}")
