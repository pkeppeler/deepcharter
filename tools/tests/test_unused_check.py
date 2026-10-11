"""Unused-code gate (#362): each kind of finding is red on a fixture, each opt-out is green, and the real tools tree is clean."""
import textwrap
import unittest

import unused_check


def found(**files: str) -> set[tuple[str, int, str, str]]:
    sources = {f"{name}.py": textwrap.dedent(source).lstrip("\n") for name, source in files.items()}
    return {(f.path, f.line, f.kind, f.name) for f in unused_check.check(sources)}


class FindingsAreRed(unittest.TestCase):
    def test_unused_import_in_every_form(self):
        self.assertEqual(found(a="""
            import os
            import os.path as osp
            from json import dumps, loads
            print(loads)
        """), {("a.py", 1, "import", "os"), ("a.py", 2, "import", "osp"), ("a.py", 3, "import", "dumps")})

    def test_attribute_name_does_not_use_an_import(self):
        self.assertEqual(found(a="""
            from pathlib import Path
            import sys
            print(sys.path)
        """), {("a.py", 1, "import", "Path")})

    def test_unused_module_level_function_class_and_constant(self):
        self.assertEqual(found(a="""
            LIMIT = 3
            A, B = 1, 2
            def helper():
                return 1
            class Thing:
                pass
            print(B)
        """), {("a.py", 1, "name", "LIMIT"), ("a.py", 2, "name", "A"), ("a.py", 3, "name", "helper"), ("a.py", 5, "name", "Thing")})

    def test_recursion_does_not_keep_a_function_alive(self):
        self.assertEqual(found(a="""
            def walk(n):
                return walk(n - 1)
        """), {("a.py", 1, "name", "walk")})

    def test_unused_parameter(self):
        self.assertEqual(found(a="""
            def f(a, b, *rest, key=1, **extra):
                return a
            f(1, 2)
        """), {("a.py", 1, "parameter", "b"), ("a.py", 1, "parameter", "rest"), ("a.py", 1, "parameter", "key"),
               ("a.py", 1, "parameter", "extra")})

    def test_parameter_read_by_a_nested_function_is_used(self):
        self.assertEqual(found(a="""
            def f(a):
                return lambda: a
            f(1)
        """), set())

    def test_plain_store_is_not_a_read(self):
        self.assertEqual(found(a="""
            def r(a):
                a = 2
            r(1)
        """), {("a.py", 1, "parameter", "a")})

    def test_augmented_assignment_reads(self):
        self.assertEqual(found(a="""
            def r(a):
                a += 1
            r(1)
        """), set())

    def test_inner_function_that_rebinds_the_name_is_not_a_read(self):
        self.assertEqual(found(a="""
            def outer(a):
                def inner(a):
                    return a
                return inner
            def comp(a):
                return [a for a in range(3)]
            def local(a):
                def inner():
                    a = 1
                    return a
                return inner
            outer(1)
            comp(1)
            local(1)
        """), {("a.py", 1, "parameter", "a"), ("a.py", 5, "parameter", "a"), ("a.py", 7, "parameter", "a")})

    def test_closure_read_of_the_outer_name_counts(self):
        self.assertEqual(found(a="""
            def outer(a, b):
                def inner():
                    return a
                return [b for _ in range(2)], inner
            outer(1, 2)
        """), set())

    def test_method_of_a_local_base_is_checked(self):
        self.assertEqual(found(a="""
            class Base:
                def run(self):
                    return 1
            class Child(Base):
                def run(self, unused):
                    return 2
            Child().run(1)
        """), {("a.py", 5, "parameter", "unused")})

    def test_plain_enum_namedtuple_and_private_override_are_checked(self):
        self.assertEqual(found(a="""
            from enum import Enum
            from typing import NamedTuple
            from unittest import TestCase
            class Kind(Enum):
                A = 1
                def label(self, unused):
                    return 1
            class Pair(NamedTuple):
                x: int
                def total(self, unused):
                    return self.x
            class Case(TestCase):
                def _helper(self, unused):
                    return 1
            print(Kind.A.label(1), Pair(1).total(1), Case)
        """), {("a.py", 6, "parameter", "unused"), ("a.py", 10, "parameter", "unused"), ("a.py", 13, "parameter", "unused")})

    def test_neutral_decorators_are_checked(self):
        self.assertEqual(found(a="""
            from contextlib import contextmanager
            from functools import lru_cache
            class K:
                @staticmethod
                def s(unused):
                    return 1
                @classmethod
                def c(cls, unused):
                    return 1
                @property
                def p(self):
                    return 1
            @contextmanager
            def cm(unused):
                yield 1
            @lru_cache(maxsize=None)
            def cached(unused):
                return 1
            print(K, cm, cached)
        """), {("a.py", 5, "parameter", "unused"), ("a.py", 8, "parameter", "unused"),
               ("a.py", 14, "parameter", "unused"), ("a.py", 17, "parameter", "unused")})

    def test_a_name_is_alive_when_another_file_reads_it(self):
        self.assertEqual(found(a="def helper():\n    return 1\nLIMIT = 3\n", b="import a\nprint(a.helper(), a.LIMIT)\n"), set())

    def test_a_name_only_imported_elsewhere_is_alive(self):
        self.assertEqual(found(a="def helper():\n    return 1\n", b="from a import helper\nprint(helper)\n"), set())


class OptOutsAreGreen(unittest.TestCase):
    def test_marker_on_the_line_or_the_line_above(self):
        self.assertEqual(found(a="""
            import os  # unused-ok: re-exported for the plugin loader
            # unused-ok: read by an external script
            LIMIT = 3
            def f(a, b):  # unused-ok: callback signature fixed by the caller
                return 1
            f(1, 2)
        """), set())

    def test_marker_covers_a_whole_multiline_statement(self):
        self.assertEqual(found(a="""
            TABLE = {
                "a": 1,
            }  # unused-ok: read by an external script
        """), set())

    def test_empty_or_generic_reason_is_a_finding_and_suppresses_nothing(self):
        self.assertEqual(found(a="""
            import os  # unused-ok:
            import sys  # unused-ok: unused
            import re  # unused-ok: not used
            import abc  # unused-ok: re-exported for plugins
        """), {
            ("a.py", 1, "marker", "unused-ok"), ("a.py", 1, "import", "os"),
            ("a.py", 2, "marker", "unused-ok"), ("a.py", 2, "import", "sys"),
            ("a.py", 3, "marker", "unused-ok"), ("a.py", 3, "import", "re"),
        })

    def test_getattr_string_names_a_use(self):
        self.assertEqual(found(a="""
            def build_wall():
                return 1
            print(getattr(globals(), "build_wall"))
        """), set())

    def test_all_names_a_use(self):
        self.assertEqual(found(a="""
            import os
            def helper():
                return 1
            __all__ = ["os", "helper"]
        """), set())

    def test_entry_points_and_test_discovery(self):
        self.assertEqual(found(a="""
            import unittest

            def main():
                return 0

            def test_thing():
                return 1

            class CaseTest(unittest.TestCase):
                def setUp(self):
                    self.x = 1
                def test_it(self, unused_fixture):
                    pass
        """), set())

    def test_exempt_parameters(self):
        self.assertEqual(found(a="""
            from http.server import BaseHTTPRequestHandler
            def paint(canvas, _glow, _rng):
                return canvas
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self, request):
                    return 1
            class Plain:
                def stub(self, value):
                    raise NotImplementedError
                def method(self):
                    return 1
            def abstract(value):
                ...
            paint(1, 2, 3)
            print(Plain, Handler, abstract)
        """), set())

    def test_decorated_definitions_are_left_alone(self):
        self.assertEqual(found(a="""
            @register
            def helper(value):
                return 1
        """), set())


class RealTree(unittest.TestCase):
    def test_tools_tree_has_no_unused_code(self):
        self.assertEqual([str(finding) for finding in unused_check.check(unused_check.scanned_sources())], [])

    def test_scan_covers_the_extensionless_script_and_nested_packages(self):
        scanned = unused_check.scanned_sources()
        self.assertIn("tools/diff-stills", scanned)
        self.assertIn("tools/colony/kit.py", scanned)
        self.assertIn("tools/tests/unused_check.py", scanned)


if __name__ == "__main__":
    unittest.main()
