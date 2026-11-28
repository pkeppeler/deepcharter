"""The members of a Java file and which of them a change touched, for tools/ci_reach.py (ADR 0121).

A light parser over comment-free code with emptied literals: a type body splits into members at its top level (a field or constant ending
in `;`, a method, constructor or nested type with a `{...}` body, an enum's constants before its first `;`). Each member has a key, its
simple name (overloads share one), and a normalised text. Comparing the two sides' members gives the names a change touched; anything the
parser cannot place (the top-level type's header, an initializer block, unbalanced braces) answers None, which means the whole file."""
import functools
import re
from dataclasses import dataclass, field

_ANNOTATION = re.compile(r"@(?!interface\b)[\w.]+(?:\s*\((?:[^()]|\([^()]*\))*\))?")
_TYPE_HEADER = re.compile(r"\b(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)")
_IDENT = re.compile(r"[A-Za-z_]\w*")
_DECLARATOR = re.compile(r"([A-Za-z_]\w*)\s*(?:\[\s*\])*\s*$")
_KEYWORDS = frozenset(
    "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for "
    "goto if implements import instanceof int interface long native new package private protected public return short static strictfp "
    "super switch synchronized this throw throws transient try void volatile while true false null"
    .split()
)
# Names a change to an enum's constants can reach without naming the constant: iterating, indexing or mapping all of them.
ENUM_WIDE = frozenset({"values", "valueOf", "ordinal", "EnumMap", "EnumSet", "compareTo"})
WHOLE = None
# The marker changed_names adds when the top-level type's header changed.
HEADER = "<header>"


def tokens(code: str) -> frozenset[str]:
    return frozenset(_IDENT.findall(code)) - _KEYWORDS


@dataclass
class Members:
    texts: dict[str, str] = field(default_factory=dict)
    overrides: set[str] = field(default_factory=set)
    methods: set[str] = field(default_factory=set)
    constants: set[str] = field(default_factory=set)
    # A field's initializer `new R(a, b, c)` of a record R declared in the file: field key to (R, the argument texts).
    constructions: dict[str, tuple[str, list[str]]] = field(default_factory=dict)
    components: dict[str, list[str]] = field(default_factory=dict)
    fields: set[str] = field(default_factory=set)
    # An instance field with an initializer, to the constructor that runs it (`Helper__new`).
    initialised: dict[str, str] = field(default_factory=dict)

    def add(self, key: str, text: str) -> None:
        self.texts[key] = (self.texts.get(key, "") + " " + " ".join(text.split())).strip()

    @functools.cached_property
    def used(self) -> list[tuple[str, frozenset[str]]]:
        """Each member's simple name and the names its text uses, once parsing is done."""
        return [(simple(k), tokens(t)) for k, t in self.texts.items() if not k.startswith("<")]


class Unparsable(Exception):
    pass


def _matching(code: str, start: int, open_: str = "{", close: str = "}") -> int:
    """The index just past the bracket that closes the one at `start`."""
    depth = 0
    for i in range(start, len(code)):
        if code[i] == open_:
            depth += 1
        elif code[i] == close:
            depth -= 1
            if depth == 0:
                return i + 1
    raise Unparsable(f"unbalanced {open_} at {start}")


def _split_top(text: str) -> list[str]:
    """The comma-separated parts of `text` outside any bracket."""
    parts, depth, start = [], 0, 0
    for i, ch in enumerate(text):
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
    parts.append(text[start:])
    return [p for p in (p.strip() for p in parts) if p]


def _top_level_paren(text: str) -> int:
    """The index of the first `(` outside any bracket, or -1."""
    depth = 0
    for i, ch in enumerate(text):
        if ch == "(" and depth == 0:
            return i
        if ch in "[{<":
            depth += 1
        elif ch in "]}>":
            depth -= 1
    return -1


def _has_top_level_equals(text: str) -> bool:
    depth = 0
    for i, ch in enumerate(text):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "=" and depth == 0 and text[i + 1 : i + 2] != "=" and text[i - 1 : i] not in "<>!=":
            return True
    return False


def _statement_end(code: str, i: int) -> int:
    """The index just past the `;` that ends the statement starting at `i`, skipping brackets (an anonymous class, a lambda body)."""
    depth = 0
    while i < len(code):
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == ";" and depth == 0:
            return i + 1
        i += 1
    raise Unparsable("statement without ;")


def _field_name(statement: str) -> str | None:
    head = statement.split("=", 1)[0] if _has_top_level_equals(statement) else statement.rstrip(";")
    m = _DECLARATOR.search(_ANNOTATION.sub(" ", head).strip())
    return m[1] if m and m[1] not in _KEYWORDS else None


def _body(code: str, members: Members, kind: str, prefix: str) -> None:
    """Splits a type body (between its braces) into members."""
    i = 0
    if kind == "enum":
        end = _enum_constants_end(code)
        for constant in _split_top(code[:end]):
            name = _IDENT.match(constant.strip())
            if name:
                members.add(f"{prefix}.{name[0]}", constant)
                members.constants.add(name[0])
        i = end + 1 if end < len(code) else end
    while i < len(code):
        while i < len(code) and code[i] in " \t\r\n;":
            i += 1
        if i >= len(code):
            break
        j = i
        while j < len(code) and code[j] not in ";{":
            j = _matching(code, j, "(", ")") if code[j] == "(" else j + 1
        if j >= len(code):
            raise Unparsable("member without ; or {")
        header = code[i:j]
        if code[j] == ";":
            _plain(header + ";", members, prefix)
            i = j + 1
            continue
        if _has_top_level_equals(header):  # a field whose initializer has a block: an anonymous class, a lambda, an array
            end = _statement_end(code, i)
            _plain(code[i:end], members, prefix)
            i = end
            continue
        end = _matching(code, j)
        _block(header, code[j + 1 : end - 1], members, prefix)
        i = end


def _enum_constants_end(code: str) -> int:
    depth = 0
    for i, ch in enumerate(code):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == ";" and depth == 0:
            return i
    return len(code)


def _plain(statement: str, members: Members, prefix: str) -> None:
    """A member ending in `;`: a field or an abstract method."""
    bare = _ANNOTATION.sub(" ", statement)
    paren = _top_level_paren(bare)
    if paren >= 0 and not _has_top_level_equals(bare[:paren]):
        name = _DECLARATOR.search(bare[:paren].strip())
        if name:
            members.add(f"{prefix}.{name[1]}", statement)
            members.methods.add(name[1])
            if "@Override" in statement:
                members.overrides.add(name[1])
        return
    name = _field_name(statement)
    if name is None:
        raise Unparsable(f"a member with no name: {statement[:60]}")
    members.add(f"{prefix}.{name}", statement)
    members.fields.add(name)
    if _has_top_level_equals(statement) and not re.search(r"\bstatic\b", statement.split("=", 1)[0]):
        members.initialised[name] = f"{simple(prefix)}__new"  # an instance field's initializer runs in every constructor
    construction = re.search(r"=\s*new\s+([A-Za-z_]\w*)(?:\s+\w+__new)?\s*\(", statement)
    if construction:
        start = construction.end() - 1
        args = statement[start + 1 : _matching(statement, start, "(", ")") - 1]
        members.constructions[f"{prefix}.{name}"] = (construction[1], _split_top(args))


def _block(header: str, body: str, members: Members, prefix: str) -> None:
    bare = _ANNOTATION.sub(" ", header)
    declared = _TYPE_HEADER.search(bare)
    if declared and _top_level_paren(bare[: declared.start()]) < 0:
        _type(declared[1], declared[2], bare, body, members, prefix)
        return
    paren = _top_level_paren(bare)
    words = _IDENT.findall(bare)
    if paren < 0 and words and words[-1] == simple(prefix):  # a record's compact constructor
        members.add(f"{prefix}.{words[-1]}__new", header + "{" + body + "}")
        return
    if paren < 0:  # `static { }` or `{ }`: runs on class load and reaches anything, so a change to one is a change to the whole file
        members.add(f"<init {prefix}>", header + "{" + body + "}")
        return
    name = _DECLARATOR.search(bare[:paren].strip())
    if not name:
        raise Unparsable(f"a block with no name: {header[:60]}")
    key = f"{name[1]}__new" if name[1] == simple(prefix) else name[1]  # a constructor: what `new X(` calls (ci_reach.member_code)
    members.add(f"{prefix}.{key}", header + "{" + body + "}")
    members.methods.add(key)
    if "@Override" in header:
        members.overrides.add(name[1])


def _type(kind: str, name: str, header: str, body: str, members: Members, prefix: str = "") -> None:
    qualified = f"{prefix}.{name}".lstrip(".")
    rest = header[header.index(name) + len(name) :]
    if kind == "record":
        paren = rest.find("(")
        close = _matching(rest, paren, "(", ")")
        components = []
        for component in _split_top(rest[paren + 1 : close - 1]):
            m = _DECLARATOR.search(_ANNOTATION.sub(" ", component).strip())
            if m:
                components.append(m[1])
                members.add(f"{qualified}.{m[1]}", component)
                members.fields.add(m[1])
        members.components[name] = components
        rest = rest[close:]
    members.add(f"<type {qualified}>", f"{kind} {name} {rest}")
    _body(body, members, kind, qualified)


def parse(code: str) -> tuple[str, Members]:
    """(the top-level type's name, its members) for comment-free code with literals replaced (ci_reach.member_code). Annotations stay in
    the members' text: `@Override` marks a method the game calls."""
    members = Members()
    stripped = re.sub(r"^\s*(package|import)\b[^;]*;", " ", code, flags=re.M)
    declared = _TYPE_HEADER.search(stripped)
    if not declared:
        raise Unparsable("no type")
    brace = stripped.index("{", declared.end())
    end = _matching(stripped, brace)
    if stripped[end:].strip():
        raise Unparsable("more than one top-level type")
    _type(declared[1], declared[2], _ANNOTATION.sub(" ", stripped[:brace]), stripped[brace + 1 : end - 1], members)
    return declared[2], members


def changed_names(old_code: str | None, new_code: str | None) -> frozenset[str] | None:
    """The member names a change touched (added, removed or changed), closed over the file's own members that use them; None for the
    whole file: either side missing or unparsable, or an initializer changed. A change to the top-level type's header (what it extends or
    implements, its annotations) adds the names it added or removed and `HEADER`, for the caller to decide."""
    if old_code is None or new_code is None:
        return WHOLE
    try:
        top_old, old = parse(old_code)
        top_new, new = parse(new_code)
    except (Unparsable, ValueError):
        return WHOLE
    if top_old != top_new:
        return WHOLE
    keys = {k for k in old.texts.keys() | new.texts.keys() if old.texts.get(k) != new.texts.get(k)}
    names = set()
    if any(k.startswith("<init ") for k in keys):
        return WHOLE
    for key in keys:
        if key == f"<type {top_new}>":
            names |= tokens(old.texts.get(key, "")) ^ tokens(new.texts.get(key, ""))
            names.add(HEADER)
            continue
        if key.startswith("<type "):
            nested = key[len("<type ") : -1].rsplit(".", 1)[-1]
            names.add(nested)
            names |= set(new.components.get(nested, []) + old.components.get(nested, []))
            continue
        after, before = new.constructions.get(key), old.constructions.get(key)
        if after and before and after[0] == before[0]:
            # A record built from arguments in the file (`DEFAULT = new Movement(0.2f, ...)`, the tuning convention): the components
            # whose argument changed, matched by name, so that a reader of another component is not reached.
            now = _by_component(new.components.get(after[0]), after[1])
            then = _by_component(old.components.get(before[0]), before[1])
            if now is not None and then is not None:
                names |= {c for c in now.keys() | then.keys() if now.get(c) != then.get(c)}
                continue
        names.add(simple(key))
    if names & (new.constants | old.constants):
        names |= ENUM_WIDE
    return frozenset(close_over(new, names))


def _by_component(components: list[str] | None, args: list[str]) -> dict[str, str] | None:
    if components is None or len(components) != len(args):
        return None
    return {c: " ".join(a.split()) for c, a in zip(components, args)}


def simple(key: str) -> str:
    return key.rsplit(".", 1)[-1]


def close_over(members: Members, names: set[str]) -> set[str]:
    """`names` and the simple name of every member whose text uses one, until nothing more is added."""
    found = set(names)
    grew = True
    while grew:
        grew = False
        for name, used in members.used:
            if name not in found and used & found:
                found.add(name)
                grew = True
        constructors = {members.initialised[f] for f in found if f in members.initialised} - found
        if constructors:
            found |= constructors
            grew = True
    return found


def members_using(
    members: Members, names: frozenset[str] | set[str], handles: frozenset[str] | set[str] | None = None, close: bool = True
) -> set[str]:
    """The simple names of the members whose text uses one of `names`, closed over the file's own members. With `handles`, a member
    counts only if it also names one of them (the type the names belong to, or a field holding one): `report` alone is anyone's."""
    direct = {name for name, used in members.used if used & names and (handles is None or used & handles)}
    return close_over(members, direct) if direct and close else direct
