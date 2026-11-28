"""Which GameTest classes a pull request's change can reach, for tools/ci_affected.py (ADR 0120, amending ADR 0116).

The graph is a file-level reference graph over src/main, src/client and src/gametest: file A has an edge to file B when A imports B or
names a type B declares, resolved the way javac would see it (A's own types, then its single-type imports, then its package, then its
wildcard imports; a fully qualified name resolves as written; `Outer.Inner` is a use of Outer's file). A name none of those explain falls
back to every file that declares it, so a reference the resolver misses still counts. A test that names a lang key or id a main or client
file names also refers to that file: a test that asserts a message by its key uses the code that sends it without naming its class.

A change travels as member names (tools/ci_members.py). A changed file passes on the names of the members its change touched; a file that
refers to it is reached when it uses one of those names, and passes on the names of its own members that use them. A file passes on all
of itself (WHOLE) when:

- the change cannot be narrowed: no old side, a file that does not parse, the type's header or an initializer changed, a deleted file;
- a touched member overrides another (`@Override`): the game, not our code, calls it;
- a touched method is used only by init or registration code (a callback the game calls, registered from an `XInit` or a mixin);
- it extends or implements the file it was reached from, and that file passed on all of itself.

Two cuts keep this from selecting everything: a listed test class is a leaf, and a hub passing on all of itself stops there (a hub is a
file more than `HUB_FAN_IN` files refer to: PodEntity, a registry, the shared test kit). That a change reaches a file every second test
touches says nothing about which tests it affects. A changed hub still passes on the names its change touched.

This is a static over-approximation of who calls what, by name: a member name two files share counts for both. What it cannot see is
behaviour the game drives through a hub (a tick in PodEntity that calls a changed class): that, and anything else it misses, the batch's
full suites catch before the change lands (ADR 0116)."""
import difflib
import functools
import hashlib
import json
import re
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

import ci_members as members_of

ROOTS = ("src/main/java", "src/client/java", "src/gametest/java")
# A file more files than this refer to directly is a hub (see the module docstring). Measured on the tree of #1721: the median file has
# one referrer, and the 53 above 40 are the pod entity, the registries, the tuning records, the sound and theme tables and the shared test
# kit; below it sit the feature classes a change is about.
HUB_FAN_IN = 40
# How many files from a changed one a change is followed: a test that calls what calls what calls the change is reached, one further out is
# not. Measured on the last 20 merged PRs (ADR 0120): at 4 the chains run through the feature cores (lava watch, lining, payloads) and
# select over half the suite; at 3 the tests selected are the ones about the change.
MAX_HOPS = 2
WHOLE = members_of.WHOLE

_COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*|(\"\"\"(?:\\.|.)*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*')", re.S)
_STRING = re.compile(r"\"\"\"((?:\\.|.)*?)\"\"\"|\"((?:\\.|[^\"\\\n])*)\"", re.S)
_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
_IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)(\.\*)?\s*;", re.M)
_TYPE_DECLARATION = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([A-Z][A-Za-z0-9_]*)")
_SUPERTYPES = re.compile(r"\b(?:extends|implements)\s+([^{;]+)")
# A dotted chain of identifiers not itself after a dot: `PodEntity`, `PodEntity.Mode.IDLE`, `io.x.pod.PodEntity`, `foo.bar()`.
_CHAIN = re.compile(r"(?<![\w.])([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)")
# A lang key, registry id or resource path (`deepcharter.pod.rescue.left`, `deepcharter:tow_cable`, `hud/fuel`), and the prefix of one the
# code completes (`deepcharter.pod.rescue.`): at least two segments, so `deepcharter.` alone is no key.
_KEY = re.compile(r"^[a-z0-9_]+(?:[.:/][a-z0-9_]+)+$")
_KEY_PREFIX = re.compile(r"^[a-z0-9_]+(?:[.:/][a-z0-9_]+)+[.:/]$")


def _code(text: str) -> str:
    """The text with comments removed and every literal emptied, so neither a word in a comment nor one in a string is a use."""
    return _COMMENTS.sub(lambda m: '""' if m.group(1) else " ", text)


def literal_token(content: str) -> str:
    """The identifier a literal becomes in member code: its content decides it, so a changed string is a changed member."""
    return "S_" + hashlib.sha1(content.encode()).hexdigest()[:12]


def member_code(text: str) -> str:
    """The text with comments removed and each literal replaced by a quoted `literal_token`, newlines kept."""

    def replace(m: re.Match[str]) -> str:
        if not m.group(1):
            return "\n" * m.group(0).count("\n") or " "
        body = m.group(1)
        inner = body[3:-3] if body.startswith('"""') else body[1:-1]
        return '"' + literal_token(inner) + '"' + "\n" * body.count("\n")

    code = _COMMENTS.sub(replace, text)
    # A constructor call is a use of the constructor, `X__new`, not of everything X declares (members_of names a constructor so).
    code = re.sub(r"\bnew\s+([A-Z]\w*)(\s*(?:<[^<>()]*>)?\s*\()", r"new \1 \1__new\2", code)
    return re.sub(r"\b([A-Z]\w*)\s*::\s*new\b", r"\1::\1__new", code)


def _literals(text: str) -> frozenset[str]:
    without_comments = _COMMENTS.sub(lambda m: m.group(0) if m.group(1) else " ", text)
    return frozenset(a or b for a, b in _STRING.findall(without_comments) if (a or b).strip())


def _names(code: str) -> tuple[set[str], set[str]]:
    """(simple names used unqualified, fully qualified candidates) in a piece of code: the first capitalised part of each chain, and
    for a chain that starts in lower case (a package), the chain up to its first capitalised part."""
    simple, qualified = set(), set()
    for chain in _CHAIN.findall(code):
        parts = [p.strip() for p in chain.split(".")]
        first = next((i for i, p in enumerate(parts) if p[:1].isupper()), None)
        if first is None:
            continue
        if first == 0:
            simple.add(parts[0])
        else:
            qualified.add(".".join(parts[: first + 1]))
    return simple, qualified


@dataclass(frozen=True)
class Source:
    path: str
    text: str
    package: str
    declared: frozenset[str]
    imports: tuple[tuple[str, bool, bool], ...]  # (name, is static, is a wildcard)
    simple: frozenset[str]
    qualified: frozenset[str]
    literals: frozenset[str]
    supertypes: frozenset[str]


def parse(path: str, text: str) -> Source:
    code = _code(text)
    package = (m := _PACKAGE.search(code)) and m[1] or ""
    imports = tuple((m[2], bool(m[1]), bool(m[3])) for m in _IMPORT.finditer(code))
    body = _IMPORT.sub(" ", _PACKAGE.sub(" ", code))
    declared = frozenset(set(_TYPE_DECLARATION.findall(code)) | {Path(path).stem})
    simple, qualified = _names(body)
    supertypes = frozenset(n for m in _SUPERTYPES.finditer(body) for n in re.findall(r"[A-Z]\w*", m[1]))
    return Source(path, text, package, declared, imports, frozenset(simple - declared), frozenset(qualified), _literals(text), supertypes)


class Graph:
    def __init__(self, sources: Iterable[Source], leaves: frozenset[str] = frozenset()):
        self.sources = {s.path: s for s in sources}
        self.leaves = leaves
        self.by_fqn = {f"{s.package}.{Path(s.path).stem}".lstrip("."): s.path for s in self.sources.values()}
        self.declaring: dict[str, set[str]] = {}
        for s in self.sources.values():
            for name in s.declared:
                self.declaring.setdefault(name, set()).add(s.path)
        self.refers = {path: self.resolve(s, s.simple, s.qualified, with_imports=True) - {path} for path, s in self.sources.items()}
        self.referred_by: dict[str, set[str]] = {path: set() for path in self.sources}
        for path, targets in self.refers.items():
            for target in targets:
                self.referred_by[target].add(path)
        self.hubs = frozenset(p for p, by in self.referred_by.items() if len(by) > HUB_FAN_IN)
        self.key_referrers: dict[str, set[str]] = {}
        for test, targets in self._shared_keys().items():
            for target in targets:
                self.key_referrers.setdefault(target, set()).add(test)

    def _shared_keys(self) -> dict[str, set[str]]:
        """Test files to the main and client files that name a key the test names (the key itself, or a dotted prefix of it the code
        completes)."""
        exact: dict[str, set[str]] = {}
        prefixes: dict[str, set[str]] = {}
        for path, source in self.sources.items():
            if path.startswith("src/gametest/"):
                continue
            for literal in source.literals:
                if _KEY.match(literal):
                    exact.setdefault(literal, set()).add(path)
                elif _KEY_PREFIX.match(literal):
                    prefixes.setdefault(literal, set()).add(path)
        found: dict[str, set[str]] = {}
        for path, source in self.sources.items():
            if not path.startswith("src/gametest/"):
                continue
            for literal in source.literals:
                if not _KEY.match(literal):
                    continue
                targets = set(exact.get(literal, ()))
                for end in range(len(literal) - 1, 0, -1):
                    if literal[end] in ".:/" and (more := prefixes.get(literal[: end + 1])):
                        targets |= more
                if targets:
                    found.setdefault(path, set()).update(targets - {path})
        return found

    def file_of(self, fqn: str) -> str | None:
        """The file declaring this name or the longest prefix of it (`a.Outer.Inner` is in a/Outer.java)."""
        parts = fqn.split(".")
        for end in range(len(parts), 0, -1):
            if (path := self.by_fqn.get(".".join(parts[:end]))) is not None:
                return path
        return None

    def resolve(self, source: Source, simple: Iterable[str], qualified: Iterable[str], with_imports: bool = False) -> set[str]:
        found = set()
        single = {}
        wildcards = []
        for name, static, wildcard in source.imports:
            path = self.file_of(name)
            if path is not None and with_imports:
                found.add(path)
            if wildcard:
                wildcards.append(name)
            elif not static:
                single[name.rsplit(".", 1)[-1]] = name
        for name in simple:
            if name in source.declared:
                continue
            if name in single:
                if (path := self.file_of(single[name])) is not None:
                    found.add(path)
                continue
            local = self.by_fqn.get(f"{source.package}.{name}".lstrip("."))
            if local is not None:
                found.add(local)
                continue
            via_wildcard = {p for w in wildcards if (p := self._in_wildcard(w, name)) is not None}
            if via_wildcard:
                found |= via_wildcard
                continue
            # Unexplained (a type inherited from a supertype, a use the resolver does not follow): every file that declares the name.
            found |= self.declaring.get(name, set())
        for name in qualified:
            if (path := self.file_of(name)) is not None:
                found.add(path)
        return found

    def _in_wildcard(self, wildcard: str, name: str) -> str | None:
        if (path := self.by_fqn.get(f"{wildcard}.{name}")) is not None:
            return path
        outer = self.by_fqn.get(wildcard)
        return outer if outer is not None and name in self.sources[outer].declared else None

    # Members

    @functools.cache
    def members(self, path: str) -> members_of.Members | None:
        try:
            return members_of.parse(member_code(self.sources[path].text))[1]
        except (members_of.Unparsable, ValueError):
            return None

    @functools.cache
    def tokens(self, path: str) -> frozenset[str]:
        return members_of.tokens(member_code(self.sources[path].text))

    def names_of(
        self, path: str, used: frozenset[str] | set[str], handles: frozenset[str] | set[str] | None = None, close: bool = True
    ) -> frozenset[str] | None:
        """What `path` passes on when it uses `used` (with one of `handles` in the same member, when given): its members that do, and
        with `close` the members that use those, or WHOLE when it does not parse or one of them is an override. A hub is neither closed
        over nor made whole by an override: in a class most tests touch, what calls what calls the change is half the class, and an override
        there is called in every test."""
        members = self.members(path)
        if members is None:
            return WHOLE
        hub = path in self.hubs
        names = members_of.members_using(members, used, handles, close and not hub)
        return WHOLE if names & members.overrides and not hub else frozenset(names)

    def handles(self, referrer: str, path: str) -> frozenset[str]:
        """How `referrer` names what `path` declares: the type names, their lower-camel forms (a field or accessor called after the type,
        `wrecks()`), the names it imports statically from it, and its own fields whose declaration names one of the types."""
        declared = self.sources[path].declared
        found = set(declared) | {d[:1].lower() + d[1:] for d in declared} | self._static_names(referrer, path)
        members = self.members(referrer)
        if members is not None:
            found |= {
                members_of.simple(k)
                for k, t in members.texts.items()
                if members_of.simple(k) in members.fields and members_of.tokens(t) & declared
            }
        return frozenset(found)

    def seed_names(self, path: str, old: str | None, new: str | None) -> frozenset[str] | None:
        """What a changed file passes on: the members its change touched. WHOLE when that cannot be told, and when the change is to code
        the game calls (an override, the type's header) in a file that is no hub; a hub passes on only the names, as a hub reached whole
        passes on nothing (module docstring)."""
        names = members_of.changed_names(None if old is None else member_code(old), None if new is None else member_code(new))
        members = self.members(path)
        if names is WHOLE or members is None:
            return WHOLE
        if members_of.HEADER in names or names & members.overrides:
            return names - {members_of.HEADER} if path in self.hubs else WHOLE
        return names

    def literal_names(self, path: str, literals: set[str]) -> frozenset[str] | None:
        """What `path` passes on when these of its literals changed meaning (a lang key's text, a sound): the members that hold them."""
        names = self.names_of(path, {literal_token(lit) for lit in literals})
        return WHOLE if names is not WHOLE and not names else names

    @functools.cache
    def is_wiring(self, path: str) -> bool:
        """Init or registration code: reached from an entry point or a mixin, and reaching no test even whole."""
        return self._wired(path) and not self.reach({path: WHOLE}, callbacks=False) & self.leaves

    def _wired(self, path: str) -> bool:
        seen, pending = {path}, [path]
        while pending:
            current = pending.pop()
            if re.search(r"/(DeepCharter|DeepCharterClient)\.java$|/mixin/", current):
                return True
            for referrer in self.referred_by.get(current, ()):
                if referrer not in seen and referrer not in self.leaves:
                    seen.add(referrer)
                    pending.append(referrer)
        return False

    def reach(
        self,
        seeds: dict[str, frozenset[str] | None],
        via: dict[str, str] | None = None,
        callbacks: bool = True,
        tests_only: frozenset[str] | set[str] = frozenset(),
    ) -> set[str]:
        """Every file the seeds reach (module docstring): the seeds, and each file that uses what a reached file passes on, at most
        `MAX_HOPS` game files from a seed. A seed in `tests_only` passes on only to tests, as a hub reached does. `via`, when given,
        collects the file each was first reached from, for a log that says why."""
        via = {} if via is None else via
        passed: dict[str, frozenset[str] | None] = {}
        hops: dict[str, int] = {}
        pending: list[str] = []

        def offer(path: str, names: frozenset[str] | None, depth: int, source: str | None = None) -> None:
            if path not in hops and source is not None:
                via[path] = source
            held = passed.get(path, frozenset())
            wider = path not in passed or (held is not WHOLE and (names is WHOLE or not names <= held))  # type: ignore[operator]
            if not wider and depth >= hops[path]:
                return
            passed[path] = WHOLE if names is WHOLE or held is WHOLE else names | held  # type: ignore[operator]
            hops[path] = min(depth, hops.get(path, depth))
            pending.append(path)

        seed_done: set[str] = set()
        for path, names in seeds.items():
            if path in self.sources:
                offer(path, names, 0)
        while pending:
            pending.sort(key=lambda p: -hops[p])
            path = pending.pop()
            names, depth = passed[path], hops[path]
            if depth >= MAX_HOPS or path not in seeds and (path in self.leaves or path in self.hubs and names is WHOLE):
                continue
            if names is not WHOLE and not names:
                continue
            # A hub reached for some of its members (`PodRegistry.TOW_CABLE`) passes them on to the tests that use them, and no further.
            # A changed hub passes on what its change touched in full once; names it is reached for later are as any hub's.
            only_tests = path in tests_only or path in self.hubs and (path not in seeds or path in seed_done)
            seed_done.add(path)
            source = self.sources[path]
            for test in self.key_referrers.get(path, ()):
                offer(test, frozenset(), depth, path)
            for referrer in self.referred_by[path]:
                if only_tests and referrer not in self.leaves:
                    continue
                inherits = bool(self.sources[referrer].supertypes & source.declared)
                if names is WHOLE:
                    found = WHOLE if inherits else self.names_of(referrer, source.declared | self._static_names(referrer, path))
                elif inherits:
                    found = self.names_of(referrer, names)
                    found = WHOLE if found is WHOLE else found | names
                else:
                    if not self.tokens(referrer) & names:
                        continue
                    found = self.names_of(referrer, names, self.handles(referrer, path))
                    own = self.members(path)
                    used = names & self.tokens(referrer)
                    if callbacks and found and path not in self.hubs and own is not None and used & own.methods and self.is_wiring(referrer):
                        offer(path, WHOLE, depth)  # a callback: the game calls it through what registered it
                        break
                if found is WHOLE or found:
                    offer(referrer, found, depth + (not referrer.startswith("src/gametest/")), path)
        return set(hops)

    def _static_names(self, referrer: str, path: str) -> frozenset[str]:
        return frozenset(n.rsplit(".", 1)[-1] for n, static, _ in self.sources[referrer].imports if static and self.file_of(n) == path)

    def referrers_of_name(self, name: str) -> dict[str, frozenset[str] | None]:
        """What a deleted file's name reaches: each file that uses the name, resolved or not, to its members that do."""
        users = {p for p, s in self.sources.items() if name in s.simple or any(n.rsplit(".", 1)[-1] == name for n, _, _ in s.imports)}
        return {p: found for p in users if (found := self.names_of(p, {name})) is WHOLE or found}

    def changed_line_targets(self, path: str, old: str | None, new: str | None) -> dict[str, frozenset[str] | None]:
        """What the lines a change added or removed in `path` name, resolved in `path`'s own scope: a handler or renderer registered, or
        no longer, to WHOLE; a hub to the names of its members the lines use (`ScannerRegistry.SCANNER`, `DeepSound.FUEL_LOW`), which reach
        the tests that use them and no further."""
        source = self.sources[path]
        if old is None or new is None:
            code = _code(new or old or "")
        else:
            code = _code("\n".join(
                line[1:]
                for line in difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm="", n=0)
                if line[:1] in "+-" and not line.startswith(("+++", "---"))
            ))
        body = _IMPORT.sub(" ", _PACKAGE.sub(" ", code))
        simple, qualified = _names(body)
        used = members_of.tokens(body)
        found: dict[str, frozenset[str] | None] = {}
        for target in self.resolve(source, simple, qualified) - {path}:
            members = self.members(target)
            if target not in self.hubs:
                found[target] = WHOLE
            elif members is not None and (names := frozenset(used & {n for n, _ in members.used} - self.sources[target].declared)):
                found[target] = names
        return found

    def package_tests(self, path: str) -> set[str]:
        """The tests that refer directly to a file in `path`'s directory: the feature it belongs to."""
        directory = str(Path(path).parent)
        return {t for p in self.sources if str(Path(p).parent) == directory for t in self.referred_by[p] | {p} if t in self.leaves}

    def literals_matching(self, path: str, matches: Callable[[str], bool]) -> set[str]:
        return {lit for lit in self.sources[path].literals if matches(lit)}

    def files_naming(self, matches: Callable[[str], bool]) -> dict[str, set[str]]:
        """Each file with a literal that `matches`, to those literals."""
        return {p: found for p in self.sources if (found := self.literals_matching(p, matches))}


@functools.lru_cache(maxsize=4)
def graph(root: Path, leaves: frozenset[str] = frozenset()) -> Graph:
    return Graph(
        (parse(str(f.relative_to(root)), f.read_text()) for source_root in ROOTS for f in sorted((root / source_root).rglob("*.java"))),
        leaves,
    )


# Resource keys: what a changed lang file, sounds.json or asset names, and how Java names it back.


def changed_keys(old: str | None, new: str | None) -> set[str] | None:
    """The top-level keys of a JSON object that were added, removed or changed; None when a side is not a JSON object."""
    try:
        before = json.loads(old) if old is not None else {}
        after = json.loads(new) if new is not None else {}
    except json.JSONDecodeError:
        return None
    if not isinstance(before, dict) or not isinstance(after, dict):
        return None
    return {k for k in before.keys() | after.keys() if before.get(k) != after.get(k)}


def names_lang_key(key: str) -> Callable[[str], bool]:
    """A literal names a lang key when it is the key, a dotted prefix the code completes (`"deepcharter.pod." + part`), or a dotted tail
    the code prefixes (`prefix + ".label"` names every key ending so)."""

    def matches(literal: str) -> bool:
        if literal == key:
            return True
        stripped = literal.strip(".")
        if len(stripped) >= 4 and "." in stripped and literal != stripped:
            return (literal.endswith(".") and key.startswith(stripped + ".")) or (literal.startswith(".") and key.endswith("." + stripped))
        return False

    return matches


def names_sound_event(event: str) -> Callable[[str], bool]:
    return lambda literal: literal == event or literal == f"deepcharter:{event}"


def names_asset(relative: str) -> Callable[[str], bool]:
    """A literal names an asset (`textures/gui/sprites/hud/fuel.png`, relative to `assets/<namespace>/`) when, without its namespace and
    extension, it is a tail of the asset's path of at least two segments (`hud/fuel`, `gui/sprites/hud/fuel`, the whole path)."""
    stem = relative.rsplit(".", 1)[0]

    def matches(literal: str) -> bool:
        candidate = literal.split(":", 1)[-1]
        if candidate.endswith((".png", ".json", ".ogg")):
            candidate = candidate.rsplit(".", 1)[0]
        return "/" in candidate and (stem == candidate or stem.endswith("/" + candidate))

    return matches


def sound_events_of(sounds_json: str | None, sound_path: str) -> set[str] | None:
    """The events of a sounds.json that play a sound file (`alarm/fuel_low`); None when the file cannot be read."""
    try:
        events = json.loads(sounds_json or "null")
    except json.JSONDecodeError:
        return None
    if not isinstance(events, dict):
        return None
    found = set()
    for event, body in events.items():
        for sound in body.get("sounds", []) if isinstance(body, dict) else []:
            name = sound.get("name") if isinstance(sound, dict) else sound
            if isinstance(name, str) and name.split(":", 1)[-1] == sound_path:
                found.add(event)
    return found
