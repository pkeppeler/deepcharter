#!/usr/bin/env python3
"""The feature map: one graph of the whole game, generated from docs/feature-map/map.json (#510).

    python3 -I tools/feature_map.py                 write docs/FEATURE-MAP.md and docs/feature-map/index.html
    python3 -I tools/feature_map.py --check         fail when the map is invalid or either output is stale
    python3 -I tools/feature_map.py --poster FILE   also write the poster SVG (tools/feature-map.sh publishes it)
    python3 -I tools/feature_map.py --check-stills  fail when a still is not on origin/pr-media (needs git, no network)
    python3 -I tools/feature_map.py --mermaid-images  draw the icons on the Mermaid nodes (GitHub may not render them)

The skill .claude/skills/feature-map/SKILL.md says when to edit the map. Stdlib only.
"""
import argparse
import base64
import html
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = "pkeppeler/deepcharter"
SITE = "https://pkeppeler.github.io/deepcharter/"
MAP = "docs/feature-map/map.json"
MARKDOWN = "docs/FEATURE-MAP.md"
PAGE = "docs/feature-map/index.html"
ICON_DIR = "src/main/resources/assets/deepcharter/textures/"

KINDS = {
    "mechanic": "⚙️",
    "upgrade": "⬆️",
    "hazard": "☠️",
    "counter": "\U0001f6e1️",
    "goal": "\U0001f3af",
    "resource": "\U0001f48e",
    "terminal": "\U0001f5a5️",
    "consumable": "\U0001f9ea",
    "creature": "\U0001f441️",
    "place": "\U0001f4cd",
}
STATUSES = {
    "built": ("Built", "✅"),
    "in-progress": ("In progress", "\U0001f528"),
    "planned": ("Planned", "\U0001f4cb"),
    "idea": ("Idea", "\U0001f4ad"),
}
LADDER_KINDS = {"upgrade": "Upgrade ladders", "challenge": "Challenge ladders", "goal": "Goal ladders", "flow": "Flows"}
EDGE_FIELDS = ("requires", "unlocks", "counters", "feeds")
NODE_FIELDS = {"id", "kind", "title", "summary", "status", "ladder", "rung", "covers", "refs", "icon", "still", *EDGE_FIELDS}
LADDER_FIELDS = {"id", "title", "kind", "description"}
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")
COVER_RE = re.compile(r"^(item|block|entity|terminal|consumable|track|group|chapter|zone|order):[a-z0-9_.:/-]+$")
STILL_RE = re.compile(r"^[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)?/[A-Za-z0-9._-]+\.(png|jpg|gif)$")
ISSUE_RE = re.compile(r"^#[0-9]+$")
ADR_RE = re.compile(r"^adr:[0-9]{4}$")
PATH_RE = re.compile(r"^(docs|src|tools)/[A-Za-z0-9._/-]+(#[a-z0-9_-]+)?$")
CDN = (
    ("https://cdn.jsdelivr.net/npm/cytoscape@3.30.2/dist/cytoscape.min.js",
     "sha384-IWROdLKRsN1UuJywMlWl7/blXQ8GEooN2n7dzTxfEPd7ybYIKCUJ2Ol/1Gpf3YV4"),
    ("https://cdn.jsdelivr.net/npm/dagre@0.8.5/dist/dagre.min.js",
     "sha384-2IH3T69EIKYC4c+RXZifZRvaH5SRUdacJW7j6HtE5rQbvLhKKdawxq6vpIzJ7j9M"),
    ("https://cdn.jsdelivr.net/npm/cytoscape-dagre@2.5.0/cytoscape-dagre.js",
     "sha384-u69h9ebXeSjlg6q/rb1zKTRAGu/h8deCl0409xpS/QJctMKnc4M9Fzkm01VOQdeF"),
)


def repo_root():
    return Path(__file__).resolve().parent.parent


def load(root):
    return json.loads((root / MAP).read_text(encoding="utf-8"))


# ---------------------------------------------------------------- validation


def heading_slugs(text):
    """The anchors GitHub gives the headings of a markdown file."""
    slugs = set()
    for line in text.splitlines():
        match = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if match:
            slugs.add(anchor(match.group(1)))
    return slugs


def ref_problem(ref, root):
    """Why a ref is malformed or points nowhere, or None."""
    if ISSUE_RE.match(ref):
        return None
    if ADR_RE.match(ref):
        return None if list((root / "docs/adr").glob(ref[4:] + "-*.md")) else "no such ADR"
    if not PATH_RE.match(ref):
        return "not #<n>, adr:<nnnn> or a docs/, src/ or tools/ path"
    path, _, anchor = ref.partition("#")
    if not (root / path).is_file():
        return "no such file"
    if anchor and anchor not in heading_slugs((root / path).read_text(encoding="utf-8")):
        return "no such heading in " + path
    return None


def validate(data, root):
    """Every problem in the map, as sentences. Empty when the map is sound."""
    problems = []
    if set(data) != {"ladders", "nodes"}:
        return ["the map has keys %s, it needs exactly ladders and nodes" % sorted(data)]
    ladders = {}
    if not data["ladders"] or data["ladders"][0].get("id") != "core-loop":
        problems.append("the first ladder must be core-loop: the page opens with it")
    for ladder in data["ladders"]:
        lid = ladder.get("id", "?")
        if set(ladder) != LADDER_FIELDS:
            problems.append("ladder %s has fields %s, it needs %s" % (lid, sorted(ladder), sorted(LADDER_FIELDS)))
            continue
        if not ID_RE.match(lid) or lid in ladders:
            problems.append("ladder id %r is malformed or repeated" % lid)
        if ladder["kind"] not in LADDER_KINDS:
            problems.append("ladder %s has kind %r, not one of %s" % (lid, ladder["kind"], sorted(LADDER_KINDS)))
        ladders[lid] = ladder
    ids = set()
    for node in data["nodes"]:
        nid = node.get("id", "?")
        extra = set(node) - NODE_FIELDS
        missing = {"id", "kind", "title", "summary", "status", "ladder", "rung"} - set(node)
        if extra or missing:
            problems.append("node %s has unknown fields %s and lacks %s" % (nid, sorted(extra), sorted(missing)))
            continue
        if not ID_RE.match(nid) or nid in ids:
            problems.append("node id %r is malformed or repeated" % nid)
        ids.add(nid)
        if node["kind"] not in KINDS:
            problems.append("node %s has kind %r, not one of %s" % (nid, node["kind"], sorted(KINDS)))
        if node["status"] not in STATUSES:
            problems.append("node %s has status %r, not one of %s" % (nid, node["status"], sorted(STATUSES)))
        if node["ladder"] not in ladders:
            problems.append("node %s is on ladder %r, which does not exist" % (nid, node["ladder"]))
        if not isinstance(node["rung"], int) or isinstance(node["rung"], bool) or node["rung"] < 0:
            problems.append("node %s has rung %r, not a whole number from 0" % (nid, node["rung"]))
        if not node["title"].strip() or not node["summary"].strip() or "\n" in node["summary"]:
            problems.append("node %s needs a title and a one-line summary" % nid)
    for node in data["nodes"]:
        nid = node.get("id", "?")
        for field in EDGE_FIELDS:
            for target in node.get(field, []):
                if target not in ids:
                    problems.append("node %s: %s %r is not a node" % (nid, field, target))
        for cover in node.get("covers", []):
            if not COVER_RE.match(cover):
                problems.append("node %s covers %r, which is malformed" % (nid, cover))
        for ref in node.get("refs", []):
            why = ref_problem(ref, root)
            if why:
                problems.append("node %s ref %r: %s" % (nid, ref, why))
        icon = node.get("icon")
        if icon is not None and not (icon.startswith(ICON_DIR) and icon.endswith(".png") and (root / icon).is_file()):
            problems.append("node %s icon %r is not an existing .png under %s" % (nid, icon, ICON_DIR))
        still = node.get("still")
        if still is not None and not STILL_RE.match(still):
            problems.append("node %s still %r is not a pr-media path like readme/x.png or 498/x.gif" % (nid, still))
    seen = {}
    for node in data["nodes"]:
        for cover in node.get("covers", []):
            if cover in seen:
                problems.append("%s is covered by both %s and %s" % (cover, seen[cover], node["id"]))
            seen[cover] = node["id"]
    for lid in ladders:
        if not any(node.get("ladder") == lid for node in data["nodes"]):
            problems.append("ladder %s has no nodes" % lid)
    return problems


def still_url(still):
    return "https://github.com/%s/blob/pr-media/%s?raw=true" % (REPO, still)


def missing_stills(data):
    """Stills named in the map that origin/pr-media does not hold (reads the local ref, no network)."""
    out = subprocess.run(["git", "ls-tree", "-r", "--name-only", "origin/pr-media"], capture_output=True, text=True, check=True).stdout
    held = set(out.splitlines())
    return sorted({node["still"] for node in data["nodes"] if node.get("still") and node["still"] not in held})


# ---------------------------------------------------------------- shared helpers


def edges(data):
    """Every edge once, as (from, to, type). requires and unlocks are both 'next': the first must come before the second."""
    found = set()
    for node in data["nodes"]:
        nid = node["id"]
        found.update((target, nid, "next") for target in node.get("requires", []))
        found.update((nid, target, "next") for target in node.get("unlocks", []))
        found.update((nid, target, "counters") for target in node.get("counters", []))
        found.update((nid, target, "feeds") for target in node.get("feeds", []))
    return sorted(found)


def by_id(data):
    return {node["id"]: node for node in data["nodes"]}


def ladder_nodes(data, lid):
    return sorted((n for n in data["nodes"] if n["ladder"] == lid), key=lambda n: (n["rung"], n["id"]))


def icon_uri(root, icon):
    return "data:image/png;base64," + base64.b64encode((root / icon).read_bytes()).decode("ascii")


def ref_label_and_url(ref, root, relative):
    """(label, url) of a ref. relative=True gives a link from docs/FEATURE-MAP.md, False an absolute GitHub link."""
    if ISSUE_RE.match(ref):
        return ref, "https://github.com/%s/issues/%s" % (REPO, ref[1:])
    if ADR_RE.match(ref):
        path = sorted((root / "docs/adr").glob(ref[4:] + "-*.md"))[0].relative_to(root).as_posix()
        label = "ADR " + ref[4:]
    else:
        path = ref
        label = Path(ref.partition("#")[0]).name
    if relative:
        return label, path[len("docs/"):] if path.startswith("docs/") else "../" + path
    return label, "https://github.com/%s/blob/main/%s" % (REPO, path)


# ---------------------------------------------------------------- markdown


def ref_links(node, root):
    return ", ".join("[%s](%s)" % ref_label_and_url(r, root, True) for r in node.get("refs", []))


def built_count(nodes):
    return sum(1 for n in nodes if n["status"] == "built")


def mid(node_id):
    return "n_" + node_id.replace("-", "_")


def mlabel(text):
    return text.replace('"', "#quot;")


def mermaid_node(node, images):
    label = "%s %s" % (KINDS[node["kind"]], node["title"])
    if images and node.get("icon"):
        url = "https://raw.githubusercontent.com/%s/main/%s" % (REPO, node["icon"])
        return '%s@{ img: "%s", label: "%s", pos: "b", w: 32, h: 32 }' % (mid(node["id"]), url, mlabel(node["title"]))
    return '%s["%s"]' % (mid(node["id"]), mlabel(label))


EDGE_ARROW = {"next": "-->", "counters": "-. counters .->", "feeds": "== feeds ==>"}
CLASS_DEFS = (
    "classDef built fill:#1f3d2b,stroke:#6fbf73,stroke-width:2px,color:#e8f5e9",
    "classDef progress fill:#4a3a10,stroke:#f0b429,stroke-width:2px,color:#fff3d6",
    "classDef planned fill:#2e2e2e,stroke:#9e9e9e,stroke-width:1px,color:#d6d6d6",
    "classDef idea fill:#1a1a1a,stroke:#757575,stroke-width:1px,stroke-dasharray:4 3,color:#a0a0a0",
    "classDef ext fill:#ffffff,stroke:#999999,stroke-width:1px,stroke-dasharray:2 2,color:#555555",
)
STATUS_CLASS = {"built": "built", "in-progress": "progress", "planned": "planned", "idea": "idea"}


def ladder_graph(data, lid, images, stubs):
    """A Mermaid flowchart of one ladder; a node of another ladder that an edge reaches is drawn as a plain outline (the core loop leaves them out: it points at everything)."""
    members = ladder_nodes(data, lid)
    inside = {n["id"] for n in members}
    nodes = by_id(data)
    shown = [e for e in edges(data) if (e[0] in inside or e[1] in inside) and (stubs or (e[0] in inside and e[1] in inside))]
    outside = sorted({i for e in shown for i in e[:2]} - inside)
    lines = ["flowchart %s" % ("TB" if len(members) > 9 else "LR")]
    lines.extend("  " + mermaid_node(n, images) for n in members)
    lines.extend('  %s["%s"]' % (mid(i), mlabel(nodes[i]["title"])) for i in outside)
    lines.extend("  %s %s %s" % (mid(a), EDGE_ARROW[t], mid(b)) for a, b, t in shown)
    lines.extend("  " + d for d in CLASS_DEFS)
    for status, cls in STATUS_CLASS.items():
        ids = [mid(n["id"]) for n in members if n["status"] == status]
        if ids:
            lines.append("  class %s %s" % (",".join(ids), cls))
    if outside:
        lines.append("  class %s ext" % ",".join(mid(i) for i in outside))
    return "\n".join(lines)


def ladder_overview(data):
    """Ladders as nodes, with an edge wherever a node of one ladder points at a node of another."""
    nodes = by_id(data)
    pairs = sorted({(nodes[a]["ladder"], nodes[b]["ladder"]) for a, b, _ in edges(data) if nodes[a]["ladder"] != nodes[b]["ladder"]})
    lines = ["flowchart LR"]
    for ladder in data["ladders"]:
        members = ladder_nodes(data, ladder["id"])
        built = built_count(members)
        lines.append('  l_%s["%s<br/>%d of %d built"]' % (ladder["id"].replace("-", "_"), mlabel(ladder["title"]), built, len(members)))
    lines.extend("  l_%s --> l_%s" % (a.replace("-", "_"), b.replace("-", "_")) for a, b in pairs)
    return "\n".join(lines)


def cell(text):
    return html.escape(text, quote=False).replace("|", "&#124;")


def gallery(data, lid, root):
    rows = ["| Icon | Still | Feature | What it is | Status | Links |", "|---|---|---|---|---|---|"]
    for n in ladder_nodes(data, lid):
        icon = '<img src="../%s" width="32" alt="">' % n["icon"] if n.get("icon") else KINDS[n["kind"]]
        still = '<a href="%s"><img src="%s" width="160" alt=""></a>' % ((still_url(n["still"]),) * 2) if n.get("still") else "no still yet"
        name, mark = STATUSES[n["status"]]
        rows.append("| %s | %s | <a id=\"%s\"></a>**%s**<br>%s | %s | %s %s | %s |" % (
            icon, still, n["id"], cell(n["title"]), n["kind"], cell(n["summary"]), mark, name, ref_links(n, root)))
    return "\n".join(rows)


def render_markdown(data, root, images):
    out = [
        "# Deep Charter feature map",
        "",
        "<!-- Generated by tools/feature_map.py from docs/feature-map/map.json. Do not edit; edit the map and regenerate. -->",
        "",
        "Everything in the game on one page: the mechanics, the upgrade ladders, the hazards and what counters them, the goals, the "
        "resources and where they flow, the creatures and the terminals. Built features sit beside what is planned, so this page "
        "shows what exists and what comes next. The same map, with pan, zoom and filters, is the "
        "[interactive view](%s)." % SITE,
        "",
        "How it is kept current: [the feature-map skill](../.claude/skills/feature-map/SKILL.md). The source is "
        "[map.json](feature-map/map.json).",
        "",
        "## Legend",
        "",
        "| Look | Meaning |",
        "|---|---|",
        "| Solid green | Built: it is in the game today |",
        "| Solid amber | In progress: an open pull request builds it |",
        "| Solid grey | Planned: an open issue or a settled decision, not started |",
        "| Dashed grey | Idea: in the spec or the notes, not decided |",
        "| Plain dashed outline | A feature of another ladder that this one points at |",
        "",
        "Arrows: a plain arrow means \"comes before\" (needed for, leads to). A dashed arrow marked *counters* goes from a "
        "counter to the hazard it answers. A thick arrow marked *feeds* is a flow of ore, money, fuel or spoil. "
        + " ".join("%s %s." % (e, k) for k, e in KINDS.items()),
        "",
        "## The core loop",
        "",
        data["ladders"][0]["description"],
        "",
        "```mermaid",
        ladder_graph(data, data["ladders"][0]["id"], images, False),
        "```",
        "",
        gallery(data, data["ladders"][0]["id"], root),
        "",
        "## How the ladders connect",
        "",
        "```mermaid",
        ladder_overview(data),
        "```",
        "",
        "## Status at a glance",
        "",
        "| Ladder | Built | In progress | Planned | Idea |",
        "|---|---|---|---|---|",
    ]
    for ladder in data["ladders"]:
        counts = [sum(1 for n in ladder_nodes(data, ladder["id"]) if n["status"] == s) for s in STATUSES]
        out.append("| [%s](#%s) | %s |" % (ladder["title"], anchor(ladder["title"]), " | ".join(str(c) for c in counts)))
    out.append("")
    out.append("## What is coming")
    out.append("")
    for status in ("in-progress", "planned", "idea"):
        out.append("### %s" % STATUSES[status][0])
        out.append("")
        if not any(n["status"] == status for n in data["nodes"]):
            out.extend(["Nothing right now.", ""])
        for n in sorted(data["nodes"], key=lambda n: (n["ladder"], n["rung"], n["id"])):
            if n["status"] == status:
                links = ref_links(n, root)
                out.append("- **%s** (%s): %s%s" % (n["title"], n["ladder"], n["summary"], " " + links if links else ""))
        out.append("")
    for kind, heading in LADDER_KINDS.items():
        group = [l for l in data["ladders"] if l["kind"] == kind and l is not data["ladders"][0]]
        if not group:
            continue
        out.extend(["## " + heading, ""])
        for ladder in group:
            out.extend(["### " + ladder["title"], "", ladder["description"], "", "```mermaid",
                        ladder_graph(data, ladder["id"], images, True), "```", "", gallery(data, ladder["id"], root), ""])
    return "\n".join(out).rstrip("\n") + "\n"


def anchor(title):
    return re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")


# ---------------------------------------------------------------- interactive page

PAGE_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Deep Charter feature map</title>
<!-- Generated by tools/feature_map.py from docs/feature-map/map.json. Do not edit. Relative paths only: it works as a local file and on GitHub Pages. -->
<style>
  :root { color-scheme: dark; --bg:#17110e; --panel:#221913; --line:#3a2c22; --text:#e8d9c5; --dim:#a89580; --amber:#f0b429; --green:#6fbf73; }
  * { box-sizing: border-box; }
  body { margin:0; height:100vh; display:grid; grid-template-columns: 250px 1fr 340px; background:var(--bg); color:var(--text); font:13px/1.4 system-ui, sans-serif; }
  aside { background:var(--panel); overflow:auto; padding:12px; }
  #side { border-right:1px solid var(--line); }
  #panel { border-left:1px solid var(--line); }
  h1 { font-size:16px; margin:0 0 4px; } h2 { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:var(--dim); margin:14px 0 4px; }
  a { color:var(--amber); } label { display:block; cursor:pointer; } select, input[type=search] { width:100%; background:var(--bg); color:var(--text); border:1px solid var(--line); padding:4px; }
  #cy { min-width:0; } .swatch { display:inline-block; width:14px; height:14px; border:3px solid; vertical-align:-3px; margin-right:6px; border-radius:3px; }
  .badge { display:inline-block; padding:1px 6px; border-radius:8px; background:var(--bg); border:1px solid var(--line); margin-right:4px; }
  #panel img.still { max-width:100%; border:1px solid var(--line); margin:8px 0; image-rendering:auto; } #panel img.icon { width:48px; height:48px; image-rendering:pixelated; float:right; }
  #panel button { background:none; border:none; color:var(--amber); cursor:pointer; padding:0; font:inherit; text-align:left; } ul { padding-left:18px; margin:4px 0; }
  .hint { color:var(--dim); }
</style>
</head>
<body>
<aside id="side">
  <h1>Deep Charter feature map</h1>
  <div class="hint">__COUNTS__ &middot; <a href="https://github.com/__REPO__/blob/main/docs/FEATURE-MAP.md">markdown map</a></div>
  <h2>Search</h2><input id="q" type="search" placeholder="title or summary">
  <h2>Ladder</h2><select id="ladder"></select>
  <h2>Status</h2><div id="statuses"></div>
  <h2>Kind</h2><div id="kinds"></div>
  <h2>Edges</h2>
  <div><span class="swatch" style="border-color:#8c7b6a"></span>comes before</div>
  <div><span class="swatch" style="border-color:#d9534f"></span>counters (hazard)</div>
  <div><span class="swatch" style="border-color:#3fb6c4"></span>feeds (flow)</div>
  <h2>Look</h2><div class="hint">Solid green border: built. Amber: in progress. Grey: planned. Faded dashed: idea. Pick a ladder to see it with its neighbours.</div>
</aside>
<div id="cy"></div>
<aside id="panel"><div class="hint">Click a feature to see what it is, a screenshot and its links.</div></aside>
__LIBS__
<script type="application/json" id="map-data">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById('map-data').textContent);
const STATUS = { built:['Built','#6fbf73'], 'in-progress':['In progress','#f0b429'], planned:['Planned','#9e9e9e'], idea:['Idea','#757575'] };
const KIND_COLOR = { mechanic:'#4d4338', upgrade:'#3a4a5c', hazard:'#5c2f2b', counter:'#2f4a3a', goal:'#5a4a2a', resource:'#4a3a5a', terminal:'#2f4f4f', consumable:'#5a3a4a', creature:'#3b3b4f', place:'#4a4a38' };
const EDGE_COLOR = { next:'#8c7b6a', counters:'#d9534f', feeds:'#3fb6c4' };
const byId = Object.fromEntries(DATA.nodes.map(n => [n.id, n]));
const ladderById = Object.fromEntries(DATA.ladders.map(l => [l.id, l]));
const kindsOn = new Set(Object.keys(KIND_COLOR)), statusOn = new Set(Object.keys(STATUS));
const elements = DATA.nodes.map(n => ({ group:'nodes', data:Object.assign({ id:n.id, label:n.title, kind:n.kind, status:n.status, ladder:n.ladder }, n.icon ? { icon:DATA.icons[n.icon] } : {}) }))
  .concat(DATA.ladders.map(l => ({ group:'nodes', data:{ id:'hdr-' + l.id, label:l.title, ladder:l.id }, classes:'header', selectable:false, grabbable:false })))
  .concat(DATA.edges.map((e, i) => ({ group:'edges', data:{ id:'e' + i, source:e[0], target:e[1], type:e[2], cross:byId[e[0]].ladder !== byId[e[1]].ladder } })));
const cy = cytoscape({ container:document.getElementById('cy'), elements, wheelSensitivity:0.3, minZoom:0.05, maxZoom:3, style:[
  { selector:'node', style:{ width:46, height:46, shape:'round-rectangle', 'background-color':ele => KIND_COLOR[ele.data('kind')], label:'data(label)', color:'#e8d9c5', 'font-size':10, 'text-valign':'bottom', 'text-margin-y':4, 'text-wrap':'wrap', 'text-max-width':96, 'border-width':3, 'border-color':'#9e9e9e' } },
  { selector:'node[icon]', style:{ 'background-image':'data(icon)', 'background-fit':'contain', 'background-image-smoothing':'no', 'background-clip':'none', 'background-color':'#2a2018' } },
  { selector:'node[status="built"]', style:{ 'border-color':'#6fbf73' } },
  { selector:'node[status="in-progress"]', style:{ 'border-color':'#f0b429' } },
  { selector:'node[status="idea"]', style:{ 'border-style':'dashed', opacity:0.65 } },
  { selector:'node.ghost', style:{ opacity:0.4 } },
  { selector:'node.header', style:{ width:1, height:1, shape:'rectangle', 'background-opacity':0, 'border-width':0, label:'data(label)', 'text-halign':'right', 'text-valign':'center', 'text-wrap':'none', 'font-size':18, color:'#f0b429', 'text-margin-x':4, events:'no' } },
  { selector:'node:selected', style:{ 'overlay-color':'#f0b429', 'overlay-opacity':0.35, 'overlay-padding':6 } },
  { selector:'edge', style:{ width:1.5, 'curve-style':'bezier', 'target-arrow-shape':'triangle', 'line-color':ele => EDGE_COLOR[ele.data('type')], 'target-arrow-color':ele => EDGE_COLOR[ele.data('type')], opacity:0.8 } },
  { selector:'edge[type="counters"]', style:{ 'line-style':'dashed' } },
  { selector:'edge[type="feeds"]', style:{ width:3 } },
  { selector:'edge.dim', style:{ opacity:0.35 } },
  { selector:'edge.lit', style:{ opacity:1, width:3 } },
] });

function box(parent, name, items, on) {
  items.forEach(([key, text]) => {
    const l = document.createElement('label'), c = document.createElement('input');
    c.type = 'checkbox'; c.checked = true; c.onchange = () => { c.checked ? on.add(key) : on.delete(key); refresh(); };
    l.append(c, ' ' + text); document.getElementById(parent).append(l);
  });
}
box('statuses', 'status', Object.entries(STATUS).map(([k, v]) => [k, v[0]]), statusOn);
box('kinds', 'kind', Object.keys(KIND_COLOR).map(k => [k, k]), kindsOn);
const sel = document.getElementById('ladder');
sel.append(new Option('All ladders', ''));
DATA.ladders.forEach(l => sel.append(new Option(l.title, l.id)));
sel.onchange = refresh; document.getElementById('q').oninput = refresh;

const COLS = 11, CELL_W = 128, CELL_H = 108;
function gridLayout(visible) {
  const pos = {}; let y = 0;
  DATA.ladders.forEach(l => {
    const members = DATA.nodes.filter(n => n.ladder === l.id && visible.has(n.id)).sort((a, b) => a.rung - b.rung || (a.id < b.id ? -1 : 1));
    if (!members.length) return;
    const rungs = [...new Set(members.map(n => n.rung))], rows = {}, bandRows = [];
    members.forEach(n => {
      const rank = rungs.indexOf(n.rung), band = Math.floor(rank / COLS), row = rows[rank] = (rows[rank] || 0) + 1;
      bandRows[band] = Math.max(bandRows[band] || 1, row);
      pos[n.id] = { rank, band, row: row - 1 };
    });
    const offsets = bandRows.map((_, b) => bandRows.slice(0, b).reduce((a, r) => a + r, 0));
    pos['hdr-' + l.id] = { x:0, y:y };
    members.forEach(n => { const q = pos[n.id]; pos[n.id] = { x:(q.rank % COLS) * CELL_W, y:y + 50 + (offsets[q.band] + q.row) * CELL_H }; });
    y += 50 + bandRows.reduce((a, r) => a + r, 0) * CELL_H + 40;
  });
  return pos;
}
function fitWidth() {
  const bb = cy.elements(':visible').boundingBox(), z = Math.min(1.2, (cy.width() - 60) / bb.w);
  cy.viewport({ zoom:z, pan:{ x:30 - bb.x1 * z, y:20 - bb.y1 * z } });
}
function refresh() {
  const q = document.getElementById('q').value.trim().toLowerCase(), ladder = sel.value;
  const pass = n => kindsOn.has(n.kind) && statusOn.has(n.status) && (!ladder || n.ladder === ladder)
    && (!q || (n.title + ' ' + n.summary).toLowerCase().includes(q));
  const shown = new Set(DATA.nodes.filter(pass).map(n => n.id)), ghosts = new Set();
  if (ladder) DATA.edges.forEach(([a, b]) => { if (shown.has(a) && !shown.has(b)) ghosts.add(b); if (shown.has(b) && !shown.has(a)) ghosts.add(a); });
  const headers = new Set(DATA.ladders.filter(l => !ladder && DATA.nodes.some(n => n.ladder === l.id && shown.has(n.id))).map(l => 'hdr-' + l.id));
  cy.batch(() => {
    cy.nodes().forEach(n => { const id = n.id(), on = shown.has(id) || ghosts.has(id) || headers.has(id); n.style('display', on ? 'element' : 'none'); n.toggleClass('ghost', ghosts.has(id)); });
    cy.edges().forEach(e => e.style('display', !ladder && e.data('cross') ? 'none' : (e.source().visible() && e.target().visible() ? 'element' : 'none')));
  });
  if (ladder) {
    cy.elements(':visible').layout({ name:'dagre', rankDir:'LR', nodeSep:22, rankSep:70, padding:30, animate:false, fit:true }).run();
  } else {
    const pos = gridLayout(shown);
    cy.nodes(':visible').layout({ name:'preset', positions:n => pos[n.id()], animate:false, fit:false }).run();
    fitWidth();
  }
}

function el(tag, text, cls) { const e = document.createElement(tag); if (text) e.textContent = text; if (cls) e.className = cls; return e; }
function show(id) {
  const n = byId[id], p = document.getElementById('panel'), st = STATUS[n.status];
  p.replaceChildren();
  if (n.icon) { const i = el('img', '', 'icon'); i.src = DATA.icons[n.icon]; p.append(i); }
  p.append(el('h1', n.title));
  const badges = el('div'); [n.kind, st[0], ladderById[n.ladder].title].forEach(t => badges.append(el('span', t, 'badge'))); p.append(badges);
  p.append(el('p', n.summary));
  if (n.still) { const i = el('img', '', 'still'); i.src = 'https://github.com/__REPO__/blob/pr-media/' + n.still + '?raw=true'; i.alt = n.title; p.append(i); } else p.append(el('div', 'No still yet.', 'hint'));
  if (n.refs.length) { p.append(el('h2', 'Links')); const ul = el('ul'); n.refs.forEach(r => { const li = el('li'), a = el('a', r.label); a.href = r.url; a.target = '_blank'; a.rel = 'noopener'; li.append(a); ul.append(li); }); p.append(ul); }
  const near = DATA.edges.filter(e => e[0] === id || e[1] === id);
  if (near.length) {
    p.append(el('h2', 'Connected to')); const ul = el('ul');
    near.forEach(e => { const other = e[0] === id ? e[1] : e[0], li = el('li'), b = el('button', byId[other].title);
      b.onclick = () => { cy.$(':selected').unselect(); const t = cy.getElementById(other); if (t.visible()) { t.select(); cy.animate({ center:{ eles:t } }, { duration:200 }); } show(other); };
      li.append(({ next:e[0] === id ? 'leads to ' : 'needs ', counters:e[0] === id ? 'counters ' : 'countered by ', feeds:e[0] === id ? 'feeds ' : 'fed by ' })[e[2]], b); ul.append(li); });
    p.append(ul);
  }
  if (n.covers.length) { p.append(el('h2', 'Game ids')); p.append(el('div', n.covers.join(', '), 'hint')); }
}
function resetEdges() { cy.edges().removeClass('lit'); if (!sel.value) cy.edges('[?cross]').style('display', 'none'); }
cy.on('tap', 'node', e => {
  const n = e.target; resetEdges();
  if (n.hasClass('header')) return;
  n.connectedEdges().forEach(x => { if (x.source().visible() && x.target().visible()) { x.style('display', 'element'); x.addClass('lit'); } });
  show(n.id());
});
cy.on('tap', e => { if (e.target === cy) resetEdges(); });
// Deep links: #ladder=lava shows one ladder, #node=heat-shield opens a feature (and its ladder).
const hash = new URLSearchParams(location.hash.slice(1)), want = hash.get('node');
if (want && byId[want]) sel.value = byId[want].ladder; else if (ladderById[hash.get('ladder')]) sel.value = hash.get('ladder');
refresh();
if (want && byId[want]) { cy.getElementById(want).select(); show(want); }
</script>
</body>
</html>
"""


def render_page(data, root):
    icons = {}
    nodes = []
    for node in data["nodes"]:
        full = dict(node)
        full.setdefault("covers", [])
        full["refs"] = [dict(zip(("label", "url"), ref_label_and_url(r, root, False))) for r in node.get("refs", [])]
        if node.get("icon"):
            icons[node["icon"]] = icon_uri(root, node["icon"])
        nodes.append(full)
    payload = {"ladders": data["ladders"], "nodes": nodes, "edges": [list(e) for e in edges(data)], "icons": icons}
    blob = json.dumps(payload, sort_keys=True, ensure_ascii=True, separators=(",", ":")).replace("</", "<\\/")
    libs = "".join('<script src="%s" integrity="%s" crossorigin="anonymous"></script>\n' % cdn for cdn in CDN)
    built = built_count(data["nodes"])
    counts = "%d features (%d built) on %d ladders" % (len(data["nodes"]), built, len(data["ladders"]))
    return (PAGE_TEMPLATE.replace("__LIBS__", libs)
            .replace("__COUNTS__", counts).replace("__REPO__", REPO).replace("__DATA__", blob))


# ---------------------------------------------------------------- poster

TILE, PANEL_COLUMNS, PANEL_WIDTH = 42, 13, 13 * 42 + 20
STATUS_STROKE = {"built": "#6fbf73", "in-progress": "#f0b429", "planned": "#9e9e9e", "idea": "#757575"}


def render_poster(data, root):
    """The whole map as one SVG: a titled strip of tiles per ladder, each tile the node's 16 px texture (pixelated, a data URI)
    in a status-coloured frame. Two columns of ladders, the shorter column taking the next one."""
    margin, top = 30, 130
    columns = [top, top]
    body = []
    for ladder in data["ladders"]:
        members = ladder_nodes(data, ladder["id"])
        rows = (len(members) - 1) // PANEL_COLUMNS + 1
        side = 0 if columns[0] <= columns[1] else 1
        x0, y0 = margin + side * (PANEL_WIDTH + margin), columns[side]
        body.append('<text x="%d" y="%d" class="h">%s</text>' % (x0, y0 + 16, html.escape(ladder["title"])))
        for i, n in enumerate(members):
            x, y = x0 + (i % PANEL_COLUMNS) * TILE, y0 + 26 + (i // PANEL_COLUMNS) * TILE
            dash = ' stroke-dasharray="3 2" opacity="0.7"' if n["status"] == "idea" else ""
            body.append('<g><title>%s (%s)</title><rect x="%d" y="%d" width="36" height="36" rx="5" fill="#2a2018" stroke="%s" stroke-width="3"%s/>'
                        % (html.escape(n["title"]), STATUSES[n["status"]][0], x, y, STATUS_STROKE[n["status"]], dash))
            if n.get("icon"):
                body.append('<image x="%d" y="%d" width="28" height="28" image-rendering="pixelated" style="image-rendering:pixelated" href="%s"/>'
                            % (x + 4, y + 4, icon_uri(root, n["icon"])))
            else:
                body.append('<text x="%d" y="%d" class="k" text-anchor="middle">%s</text>' % (x + 18, y + 25, KINDS[n["kind"]]))
            body.append("</g>")
        columns[side] = y0 + 26 + rows * TILE + 22
    height = max(columns)
    width = margin * 3 + PANEL_WIDTH * 2
    built = built_count(data["nodes"])
    head = [
        '<text x="%d" y="52" class="title">Deep Charter feature map</text>' % margin,
        '<text x="%d" y="80" class="d">%d features on %d ladders, %d built. Frame: green built, amber in progress, grey planned, dashed idea.</text>'
        % (margin, len(data["nodes"]), len(data["ladders"]), built),
        '<text x="%d" y="102" class="d">Interactive view: %s</text>' % (margin, SITE),
    ]
    style = ("text{font-family:ui-sans-serif,system-ui,sans-serif;fill:#e8d9c5}.title{font-size:30px;font-weight:700}.h{font-size:15px;font-weight:700;fill:#f0b429}"
             ".d{font-size:13px;fill:#a89580}.k{font-size:18px}")
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d"><style>%s</style>'
            '<rect width="100%%" height="100%%" fill="#17110e"/>%s%s</svg>\n') % (width, height, width, height, style, "".join(head), "\n".join(body))


# ---------------------------------------------------------------- command line


def outputs(data, root, images):
    return {MARKDOWN: render_markdown(data, root, images), PAGE: render_page(data, root)}


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail when the map is invalid or an output is stale")
    parser.add_argument("--check-stills", action="store_true", help="fail when a still is not on origin/pr-media")
    parser.add_argument("--poster", metavar="FILE", help="also write the poster SVG to FILE")
    parser.add_argument("--mermaid-images", action="store_true", help="draw icons on the Mermaid nodes (committed output uses text nodes)")
    args = parser.parse_args(argv)
    root = repo_root()
    data = load(root)
    problems = validate(data, root)
    if problems:
        print("\n".join("map.json: " + p for p in problems), file=sys.stderr)
        return 1
    if args.check_stills:
        gone = missing_stills(data)
        if gone:
            print("not on origin/pr-media: " + ", ".join(gone), file=sys.stderr)
            return 1
    made = outputs(data, root, args.mermaid_images)
    if args.check:
        stale = [p for p, text in made.items() if not (root / p).is_file() or (root / p).read_text(encoding="utf-8") != text]
        if stale:
            print("stale, run python3 -I tools/feature_map.py: " + ", ".join(stale), file=sys.stderr)
            return 1
    else:
        for path, text in made.items():
            (root / path).write_text(text, encoding="utf-8")
    if args.poster:
        Path(args.poster).write_text(render_poster(data, root), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
