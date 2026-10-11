"""Unused-code gate for the Python tools (#362): stdlib `ast` only, no third-party linter.

Scans every tools/**/*.py plus tools/diff-stills and reports three kinds of finding:

- import: an imported name the file never reads.
- name: a module-level function, class or constant that no scanned file references outside its own definition.
- parameter: a function parameter the function body never reads.

A reference is a bare name, an attribute name, an imported name, a `getattr(x, "name")` string or an `__all__` entry, in any scanned file
(tests included). Matching is by name, not by module, so a same-named definition elsewhere hides a dead one; a reviewer still looks.
Exempt without a marker: `main`, dunder names, `test_*` and `setUp`/`tearDown` style test discovery, `unittest` test classes, decorated
definitions (see below), `self`/`cls`, parameters starting with `_`, and stub bodies (docstring, `pass`, `...` or `raise NotImplementedError`).
A method's parameters are exempt when it is a dunder, or a public method of a class with a base that is imported or builtin (an override of an
outside API such as `BaseHTTPRequestHandler` or `TestCase`). A local base, `object`, `NamedTuple`, `Enum`, and a private `_helper` method
are checked. A decorator exempts the definition unless it is one of `NEUTRAL_DECORATORS` (`staticmethod`, `classmethod`, `property`, `cache`,
`lru_cache`, `contextmanager` and the like): those change no signature and register nothing, so the definition is checked.

A parameter is read by a Load or Del of its name in the function or in a nested function, lambda or comprehension that does not rebind
the name, or by an augmented assignment (`a += 1` reads `a`). A plain store (`a = 2`) is not a read.

Opt out with `# unused-ok: <reason>` on the finding's line, or on the line above it. A definition's span is its header; an import or
assignment's span is the whole statement. The reason is required: one word, or a stock phrase such as "not used", is itself a finding and
suppresses nothing.

Known blind spots: a parameter that every caller passes the same value (needs a call graph), a method nothing calls, a parameter
overwritten before its first read (any later Load counts), a base class that is imported but is not an outside API, and a decorator
not in NEUTRAL_DECORATORS that does not register anything.

Usage: python3 -I tools/tests/unused_check.py
"""
import ast
import builtins
import io
import re
import sys
import tokenize
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools"
EXTRA_SCRIPTS = ("diff-stills",)

MARKER = re.compile(r"#\s*unused-ok:(.*)$")
GENERIC_REASONS = {
    "not used", "unused", "needed", "needed later", "used later", "keep", "keep it", "ok", "fine", "todo", "n/a", "later",
    "required", "intentional", "on purpose", "no reason", "because", "future use", "for later", "might need",
}
FIXED_SIGNATURE_FUNCTIONS = {"load_tests", "setUpModule", "tearDownModule"}
NEUTRAL_DECORATORS = {
    "staticmethod", "classmethod", "property", "cache", "lru_cache", "cached_property", "contextmanager", "asynccontextmanager",
    "abstractmethod", "dataclass", "total_ordering", "final", "override", "overload",
}
PLAIN_BASES = {"object", "NamedTuple", "Enum", "IntEnum", "StrEnum", "Flag", "IntFlag", "Generic", "Protocol", "TypedDict"}
TEST_HOOKS = {"setUp", "tearDown", "setUpClass", "tearDownClass", "setUpModule", "tearDownModule", "load_tests", "main"}


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    kind: str
    name: str
    detail: str = ""

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: unused {self.kind} {self.name}{self.detail}"


@dataclass(frozen=True)
class Candidate:
    path: str
    kind: str
    name: str
    line: int
    span: tuple[int, int]
    own_span: tuple[int, int]


def scanned_sources() -> dict[str, str]:
    """Path (relative to the repo root) to source, for every Python file the gate covers."""
    files = sorted(TOOLS.rglob("*.py")) + [TOOLS / name for name in EXTRA_SCRIPTS]
    return {str(path.relative_to(ROOT)): path.read_text(encoding="utf-8") for path in files}


def check(sources: Mapping[str, str]) -> list[Finding]:
    trees = {path: ast.parse(source, filename=path) for path, source in sources.items()}
    references = {path: index_by_name(references_in(tree)) for path, tree in trees.items()}
    findings: list[Finding] = []
    for path, tree in trees.items():
        markers, bad_markers = parse_markers(sources[path])
        findings += [Finding(path, line, "marker", "unused-ok", f": reason {reason!r} is empty or generic") for line, reason in bad_markers]
        local_reads = {node.id for node in ast.walk(tree) if isinstance(node, ast.Name)} | exported_names(tree)
        candidates = import_candidates(path, tree, local_reads) + definition_candidates(path, tree) + parameter_candidates(path, tree)
        for candidate in candidates:
            if candidate.kind == "name" and referenced_elsewhere(candidate, references):
                continue
            if suppressed(candidate.span, markers):
                continue
            findings.append(Finding(path, candidate.line, candidate.kind, candidate.name))
    return sorted(findings, key=lambda finding: (finding.path, finding.line, finding.name))


def parse_markers(source: str) -> tuple[set[int], list[tuple[int, str]]]:
    """Lines carrying a valid marker, and (line, reason) of each marker whose reason is empty or generic."""
    good: set[int] = set()
    bad: list[tuple[int, str]] = []
    for token in tokenize.generate_tokens(io.StringIO(source).readline):
        if token.type != tokenize.COMMENT:
            continue
        match = MARKER.search(token.string)
        if not match:
            continue
        reason = match.group(1).strip()
        if len(reason.split()) < 2 or reason.lower().rstrip(".") in GENERIC_REASONS:
            bad.append((token.start[0], reason))
        else:
            good.add(token.start[0])
    return good, bad


def suppressed(span: tuple[int, int], markers: set[int]) -> bool:
    first, last = span
    return any(line in markers for line in range(first - 1, last + 1))


def references_in(tree: ast.AST) -> list[tuple[str, int]]:
    """(name, line) of every read of a name in the tree."""
    found: list[tuple[str, int]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Load, ast.Del)):
            found.append((node.id, node.lineno))
        elif isinstance(node, ast.Attribute):
            found.append((node.attr, node.lineno))
        elif isinstance(node, ast.ImportFrom):
            found += [(alias.name, node.lineno) for alias in node.names]
        elif isinstance(node, ast.Call) and is_getattr(node):
            found.append((node.args[1].value, node.lineno))
    return found + [(name, 0) for name in exported_names(tree)]


def exported_names(tree: ast.AST) -> set[str]:
    return {
        element.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "__all__" for t in node.targets)
        for element in ast.walk(node.value) if is_str(element)
    }


def is_str(node: ast.AST) -> bool:
    return isinstance(node, ast.Constant) and isinstance(node.value, str)


def is_getattr(call: ast.Call) -> bool:
    return isinstance(call.func, ast.Name) and call.func.id in ("getattr", "hasattr") and len(call.args) >= 2 and is_str(call.args[1])


def index_by_name(references: list[tuple[str, int]]) -> dict[str, list[int]]:
    by_name: dict[str, list[int]] = {}
    for name, line in references:
        by_name.setdefault(name, []).append(line)
    return by_name


def import_candidates(path: str, tree: ast.AST, local_reads: set[str]) -> list[Candidate]:
    found = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            bound = [(alias.asname or alias.name.split(".")[0]) for alias in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module != "__future__":
            bound = [(alias.asname or alias.name) for alias in node.names]
        else:
            continue
        span = (node.lineno, node.end_lineno or node.lineno)
        found += [
            Candidate(path, "import", name, node.lineno, span, span)
            for name in bound if name not in local_reads and name != "*"
        ]
    return found


def definition_candidates(path: str, tree: ast.Module) -> list[Candidate]:
    found = []
    for node in module_statements(tree.body):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if registers(node) or exempt_name(node.name) or is_test_class(node):
                continue
            span = (node.lineno, node.end_lineno or node.lineno)
            found.append(Candidate(path, "name", node.name, node.lineno, (node.lineno, header_end(node)), span))
        elif isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            span = (node.lineno, node.end_lineno or node.lineno)
            for target in targets:
                for name in assigned_names(target):
                    if not exempt_name(name):
                        found.append(Candidate(path, "name", name, node.lineno, span, span))
    return found


def module_statements(body: list[ast.stmt]) -> list[ast.stmt]:
    """Top-level statements, including those inside module-level if and try blocks."""
    statements: list[ast.stmt] = []
    for node in body:
        statements.append(node)
        if isinstance(node, ast.If):
            statements += module_statements(node.body) + module_statements(node.orelse)
        elif isinstance(node, ast.Try):
            statements += module_statements(node.body) + module_statements(node.orelse) + module_statements(node.finalbody)
            for handler in node.handlers:
                statements += module_statements(handler.body)
    return statements


def assigned_names(target: ast.expr) -> list[str]:
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        return [name for element in target.elts for name in assigned_names(element)]
    return []


def exempt_name(name: str) -> bool:
    return (name.startswith("__") and name.endswith("__")) or name.startswith("test_") or name in TEST_HOOKS


def is_test_class(node: ast.AST) -> bool:
    if not isinstance(node, ast.ClassDef):
        return False
    return any(base_name(base).endswith("TestCase") for base in node.bases)


def base_name(base: ast.expr) -> str:
    if isinstance(base, ast.Attribute):
        return base.attr
    return base.id if isinstance(base, ast.Name) else ""


def header_end(node: ast.AST) -> int:
    body = getattr(node, "body")
    return max(node.lineno, body[0].lineno - 1)


def referenced_elsewhere(candidate: Candidate, references: Mapping[str, Mapping[str, list[int]]]) -> bool:
    first, last = candidate.own_span
    return any(
        not (path == candidate.path and first <= line <= last)
        for path, by_name in references.items()
        for line in by_name.get(candidate.name, ())
    )


def decorator_name(decorator: ast.expr) -> str:
    target = decorator.func if isinstance(decorator, ast.Call) else decorator
    return target.attr if isinstance(target, ast.Attribute) else target.id if isinstance(target, ast.Name) else ""


def registers(node: ast.AST) -> bool:
    """True when a decorator may register the definition or fix its signature: any decorator outside NEUTRAL_DECORATORS."""
    return any(decorator_name(d) not in NEUTRAL_DECORATORS for d in getattr(node, "decorator_list", ()))


def imported_names(tree: ast.AST) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names |= {(alias.asname or alias.name.split(".")[0]) for alias in node.names}
        elif isinstance(node, ast.ImportFrom):
            names |= {(alias.asname or alias.name) for alias in node.names}
    return names


def has_outside_base(cls: ast.ClassDef, imported: set[str]) -> bool:
    for base in cls.bases:
        expression = base.value if isinstance(base, ast.Subscript) else base
        if base_name(expression) in PLAIN_BASES:
            continue
        while isinstance(expression, ast.Attribute):
            expression = expression.value
        if isinstance(expression, ast.Name) and (expression.id in imported or hasattr(builtins, expression.id)):
            return True
    return False


COMPREHENSIONS = (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)


def scope_reads(scope: ast.AST) -> tuple[set[str], set[str]]:
    """(names the scope reads, names it binds). Reads include the free reads of nested scopes; a nested scope's own bindings do not leak."""
    reads: set[str] = set()
    bound: set[str] = set()
    declared: set[str] = set()
    if isinstance(scope, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
        arguments = scope.args
        bound |= {a.arg for a in arguments.posonlyargs + arguments.args + arguments.kwonlyargs}
        bound |= {a.arg for a in (arguments.vararg, arguments.kwarg) if a}
        pending: list[ast.AST] = [scope.body] if isinstance(scope, ast.Lambda) else list(scope.body)
    else:
        pending = list(ast.iter_child_nodes(scope))
    while pending:
        node = pending.pop()
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, *COMPREHENSIONS)):
            if not isinstance(node, COMPREHENSIONS):
                pending += node.args.defaults + [d for d in node.args.kw_defaults if d]
                pending += getattr(node, "decorator_list", [])
                if not isinstance(node, ast.Lambda):
                    bound.add(node.name)
            inner_reads, inner_bound = scope_reads(node)
            reads |= inner_reads - inner_bound
            continue
        if isinstance(node, ast.Name):
            if isinstance(node.ctx, ast.Store):
                bound.add(node.id)
            else:
                reads.add(node.id)
            continue
        if isinstance(node, ast.AugAssign) and isinstance(node.target, ast.Name):
            reads.add(node.target.id)
            pending.append(node.value)
            continue
        if isinstance(node, (ast.Global, ast.Nonlocal)):
            declared |= set(node.names)
        pending += ast.iter_child_nodes(node)
    return reads, bound - declared


def parameter_candidates(path: str, tree: ast.AST) -> list[Candidate]:
    found = []
    imported = imported_names(tree)
    owner = {id(item): cls for cls in ast.walk(tree) if isinstance(cls, ast.ClassDef) for item in cls.body}
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        if registers(node) or is_stub(node) or fixed_signature(node, owner.get(id(node)), imported):
            continue
        arguments = node.args
        every = arguments.posonlyargs + arguments.args + arguments.kwonlyargs + [a for a in (arguments.vararg, arguments.kwarg) if a]
        own_span = (node.lineno, node.end_lineno or node.lineno)
        read = scope_reads(node)[0]
        for argument in every:
            if argument.arg in ("self", "cls", *read) or argument.arg.startswith("_"):
                continue
            span = (argument.lineno, argument.end_lineno or argument.lineno)
            found.append(Candidate(path, "parameter", argument.arg, argument.lineno, span, own_span))
    return found


def is_dunder(name: str) -> bool:
    return name.startswith("__") and name.endswith("__")


def fixed_signature(function: ast.FunctionDef | ast.AsyncFunctionDef, cls: ast.ClassDef | None, imported: set[str]) -> bool:
    """True when something outside the file fixes the signature: a dunder, a unittest hook, or a public override of an outside base."""
    if is_dunder(function.name) or function.name in FIXED_SIGNATURE_FUNCTIONS:
        return True
    return cls is not None and not function.name.startswith("_") and has_outside_base(cls, imported)


def is_stub(function: ast.FunctionDef | ast.AsyncFunctionDef) -> bool:
    body = function.body
    if body and isinstance(body[0], ast.Expr) and is_str(body[0].value):
        body = body[1:]
    if not body:
        return True
    only = body[0] if len(body) == 1 else None
    if isinstance(only, ast.Pass):
        return True
    if isinstance(only, ast.Expr) and isinstance(only.value, ast.Constant) and only.value.value is Ellipsis:
        return True
    return isinstance(only, ast.Raise) and "NotImplementedError" in ast.dump(only)


def main() -> int:
    findings = check(scanned_sources())
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"unused_check: {len(findings)} finding(s). Delete the dead code, or mark it `# unused-ok: <reason>`.", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
