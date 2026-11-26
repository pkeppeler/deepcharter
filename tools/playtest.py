"""Playtest helper (#1400): build main, install it into Prism and a dedicated server, run and watch that server.

Usage: tools/py tools/playtest.py <command> [options]
  build                       fetch origin/main into a reused worktree, build the jar, package the friends build
  client [--mrpack PATH]      install the mrpack in place into the Prism instance `deepcharter`
  server-install [--zip PATH] install the server zip in place into the server dir (keeps world, whitelist, EULA)
  server-start [--wait SEC]   start the server in a detached tmux session and wait until it is ready
  server-stop | server-status | server-cmd <command...> | server-watch | ack <player> <issue> [--existing]
Server commands take --dir (default ~/deepcharter-server). Env: DEEPCHARTER_PLAYTEST_HOME (default
~/.local/share/deepcharter-playtest: build worktree, download cache), DEEPCHARTER_PRISM_INSTANCES.

Everything external (subprocess, downloads, lsof, pgrep, time) goes through `Env`, so the tests need no tmux, network or Prism.
The tool never edits options.txt, never copies a world, never touches an instance other than `deepcharter`, and accepts
no download that is not https with a matching sha512. Player chat reaches the caller only through `classify`, which
sanitises it: the log is untrusted input.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import unicodedata
import urllib.request
import uuid
import zipfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

EXIT_FAIL = 1
EXIT_EULA = 3
EXIT_REFUSED = 4
EXIT_EXITED = 5
EXIT_TIMEOUT = 6

INSTANCE_ID = "deepcharter"
SESSION = "deepcharter-server"
PLAYER = re.compile(r"[A-Za-z0-9_]{3,16}")
ISSUE = re.compile(r"[1-9][0-9]*")
TAILSCALE = ("tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale")
FEEDBACK_CAP = 300
REPO_ROOT = Path(__file__).resolve().parent.parent


class Fail(Exception):
    def __init__(self, message: str, code: int = EXIT_FAIL):
        super().__init__(message)
        self.message = message
        self.code = code


@dataclass(frozen=True)
class Env:
    home: Path
    instances: Path
    user_home: Path
    runner: Callable[..., subprocess.CompletedProcess[str]]
    download: Callable[[str], bytes]
    is_open: Callable[[Path], bool]
    prism_running: Callable[[], bool]
    sleep: Callable[[float], None]
    clock: Callable[[], float]


def _real_download(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:  # noqa: S310 - callers check the https scheme first
        return response.read()


def _real_is_open(path: Path) -> bool:
    if not path.exists():
        return False
    return subprocess.run(["lsof", "-t", str(path)], capture_output=True, text=True, check=False).stdout.strip() != ""


def _real_prism_running() -> bool:
    return subprocess.run(["pgrep", "-xi", "prismlauncher"], capture_output=True, check=False).returncode == 0


def real_env() -> Env:
    user_home = Path.home()
    instances = os.environ.get("DEEPCHARTER_PRISM_INSTANCES")
    return Env(
        home=Path(os.environ.get("DEEPCHARTER_PLAYTEST_HOME") or user_home / ".local/share/deepcharter-playtest"),
        instances=Path(instances) if instances else user_home / "Library/Application Support/PrismLauncher/instances",
        user_home=user_home,
        runner=subprocess.run,
        download=_real_download,
        is_open=_real_is_open,
        prism_running=_real_prism_running,
        sleep=time.sleep,
        clock=time.monotonic,
    )


def _call(env: Env, argv: list[str], *, cwd: Path | None = None, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    try:
        return env.runner(argv, text=True, capture_output=True, cwd=cwd, env=extra_env)
    except FileNotFoundError:
        raise Fail(f"{argv[0]} not found on this machine") from None


def _run(env: Env, argv: list[str], *, cwd: Path | None = None, extra_env: dict[str, str] | None = None) -> str:
    result = _call(env, argv, cwd=cwd, extra_env=extra_env)
    if result.returncode != 0:
        raise Fail(f"{' '.join(argv)} exited {result.returncode}: {(result.stderr or '').strip()[-2000:]}")
    return result.stdout or ""


def _safe_rel(name: str) -> PurePosixPath:
    rel = PurePosixPath(name)
    if not name or "\\" in name or rel.is_absolute() or ".." in rel.parts:
        raise Fail(f"refusing path outside the target directory: {name!r}")
    return rel


# ---------------------------------------------------------------- build


def _git(env: Env, args: list[str]) -> str:
    # A hook exports GIT_DIR and its siblings; they would make `-C` act on the wrong repository (as tests/git_env.py guards).
    clean = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    return _run(env, ["git", *args], extra_env=clean)


def build(env: Env, repo: Path) -> tuple[Path, Path]:
    """Build origin/main in the tool-owned worktree and package it. Returns (mrpack, server zip)."""
    tree = env.home / "build"
    _git(env, ["-C", str(repo), "fetch", "origin", "main"])
    if (tree / ".git").exists():
        # Tool-owned: local changes in it are discarded.
        _git(env, ["-C", str(tree), "checkout", "--force", "--detach", "origin/main"])
    else:
        env.home.mkdir(parents=True, exist_ok=True)
        _git(env, ["-C", str(repo), "worktree", "add", "--detach", str(tree), "origin/main"])
    mod_version = next(
        (line.split("=", 1)[1].strip() for line in (tree / "gradle.properties").read_text().splitlines() if line.startswith("version=")),
        "",
    )
    if not mod_version:
        raise Fail(f"no version= line in {tree / 'gradle.properties'}")
    label = f"{mod_version}-{_git(env, ['-C', str(tree), 'rev-parse', '--short=7', 'HEAD']).strip()}"
    _run(env, ["./gradlew", "jar", "-q"], cwd=tree)
    jar = tree / "build" / "libs" / f"deepcharter-{mod_version}.jar"
    if not jar.is_file():
        raise Fail(f"gradle did not produce {jar}")
    _run(env, ["tools/package-friends-build.sh", label], cwd=tree, extra_env={**os.environ, "DEEPCHARTER_JAR": str(jar)})
    dist = tree / "build" / "dist"
    return dist / f"deepcharter-friends-{label}.mrpack", dist / f"deepcharter-server-{label}.zip"


def _newest(paths: list[Path]) -> Path | None:
    return max(paths, key=lambda path: path.stat().st_mtime, default=None)


# ---------------------------------------------------------------- client


@dataclass(frozen=True)
class ClientResult:
    version: str
    instance: Path
    fov: float | None
    gui_scale: str | None


def fov_degrees(stored: str) -> float:
    """options.txt stores fov normalised: degrees = 70 + 40 * value."""
    return 70 + 40 * float(stored)


def _set_cfg(text: str, key: str, value: str) -> str:
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith(key + "="):
            lines[i] = f"{key}={value}"
            break
    else:
        at = lines.index("[General]") + 1 if "[General]" in lines else 0
        if at == 0:
            lines.insert(0, "[General]")
            at = 1
        lines.insert(at, f"{key}={value}")
    return "\n".join(lines) + "\n"


def _read_option(options: Path, key: str) -> str | None:
    if not options.is_file():
        return None
    for line in options.read_text().splitlines():
        if line.startswith(key + ":"):
            return line.split(":", 1)[1].strip()
    return None


def _cached_download(env: Env, entry: dict, name: str) -> bytes:
    expected = (entry.get("hashes") or {}).get("sha512")
    if not expected:
        raise Fail(f"{name}: the pack lists no sha512")
    cache = env.home / "cache" / expected
    if cache.is_file() and hashlib.sha512(cache.read_bytes()).hexdigest() == expected:
        return cache.read_bytes()
    urls = entry.get("downloads") or []
    if not urls or not all(url.startswith("https://") for url in urls):
        raise Fail(f"{name}: refusing a download that is not https: {urls}")
    data = env.download(urls[0])
    if hashlib.sha512(data).hexdigest() != expected:
        raise Fail(f"{name}: sha512 of the download does not match the pack")
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(data)
    return data


def _component_updates(pack_json: dict, deps: dict[str, str]) -> bool:
    """Set the components to the pack's versions; True when any changed."""
    wanted = {"net.minecraft": deps.get("minecraft"), "net.fabricmc.intermediary": deps.get("minecraft"),
              "net.fabricmc.fabric-loader": deps.get("fabric-loader")}
    changed = False
    for component in pack_json["components"]:
        version = wanted.get(component["uid"])
        if version and (component.get("version") != version or component.get("cachedVersion") != version):
            component["version"] = component["cachedVersion"] = version
            changed = True
    return changed


def client_install(env: Env, mrpack: Path | None) -> ClientResult:
    if mrpack is None:
        mrpack = _newest(list((env.home / "build" / "build" / "dist").glob("*.mrpack")))
        if mrpack is None:
            raise Fail("no built mrpack: run `build` first, or pass --mrpack")
    with zipfile.ZipFile(mrpack) as z:
        index_bytes = z.read("modrinth.index.json")
        index = json.loads(index_bytes)
        override_files = {}
        for prefix in ("overrides/", "client-overrides/"):
            for name in z.namelist():
                if name.startswith(prefix) and not name.endswith("/"):
                    override_files[str(_safe_rel(name[len(prefix):]))] = z.read(name)
    files = [f for f in index["files"] if (f.get("env") or {}).get("client") != "unsupported"]
    for entry in files:
        _safe_rel(entry["path"])
    version = index["versionId"]

    target = env.instances / INSTANCE_ID
    seed = None
    if not target.is_dir():
        seed = _newest([d for d in env.instances.glob("deepcharter*") if d.is_dir()])
        if seed is None:
            raise Fail(f"no deepcharter instance in {env.instances} to start from: Import the .mrpack into Prism once, then re-run")
    base = seed or target
    game = target / "minecraft"
    if seed is None and env.is_open(game / "logs" / "latest.log"):
        raise Fail(f"the game is running in instance {INSTANCE_ID}: close it first")

    pack_path = base / "mmc-pack.json"
    pack_json = json.loads(pack_path.read_text())
    if _component_updates(pack_json, index["dependencies"]) and env.prism_running():
        raise Fail("the pack needs a different Minecraft or loader version: Quit Prism Launcher, then re-run")

    # Everything is checked and downloaded before the first write, so a failure leaves the instance as it was.
    payloads = {entry["path"]: _cached_download(env, entry, entry["path"]) for entry in files}

    if seed is not None:
        shutil.copytree(seed, target, symlinks=True)
        cfg = (target / "instance.cfg").read_text()
        for key, value in (("name", "Deep Charter"), ("uuid", str(uuid.uuid4())), ("ManagedPackName", index.get("name", "Deep Charter"))):
            cfg = _set_cfg(cfg, key, value)
        (target / "instance.cfg").write_text(cfg)
    cfg = _set_cfg((target / "instance.cfg").read_text(), "ManagedPackVersionName", version)
    (target / "instance.cfg").write_text(cfg)
    pack_path = target / "mmc-pack.json"
    pack_path.write_text(json.dumps(pack_json, indent=4) + "\n")

    managed = target / "mrpack"
    old = []
    if (managed / "modrinth.index.json").is_file():
        old += [f["path"] for f in json.loads((managed / "modrinth.index.json").read_text()).get("files", [])]
    if (managed / "overrides.txt").is_file():
        old += [line.strip() for line in (managed / "overrides.txt").read_text().splitlines() if line.strip()]
    for path in old:
        (game / _safe_rel(path)).unlink(missing_ok=True)

    for path, data in {**payloads, **override_files}.items():
        dest = game / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    managed.mkdir(exist_ok=True)
    (managed / "modrinth.index.json").write_bytes(index_bytes)
    (managed / "overrides.txt").write_text("".join(f"{path}\n" for path in override_files))

    fov = _read_option(game / "options.txt", "fov")
    return ClientResult(version, target, None if fov is None else fov_degrees(fov), _read_option(game / "options.txt", "guiScale"))


# ---------------------------------------------------------------- server install


def _lock_names(lock: Path | str) -> set[str]:
    text = lock if isinstance(lock, str) else (lock.read_text() if lock.is_file() else "")
    return {line.split()[1] for line in text.splitlines() if len(line.split()) >= 3 and not line.startswith("#")}


def _eula_accepted(server_dir: Path) -> bool:
    eula = server_dir / "eula.txt"
    return eula.is_file() and any(line.strip().lower() == "eula=true" for line in eula.read_text().splitlines())


def _properties_keys(text: str) -> set[str]:
    return {line.split("=", 1)[0].strip() for line in text.splitlines() if "=" in line and not line.lstrip().startswith("#")}


def server_install(env: Env, zip_path: Path | None, server_dir: Path) -> list[str]:
    """Install the server zip in place. Returns warnings; raises Fail(EXIT_EULA) after installing when the EULA is not accepted."""
    if zip_path is None:
        zip_path = _newest(list((env.home / "build" / "build" / "dist").glob("deepcharter-server-*.zip")))
        if zip_path is None:
            raise Fail("no built server zip: run `build` first, or pass --zip")
    if server_running(env, server_dir):
        raise Fail("the server is running: stop it first (server-stop)")
    members: dict[str, bytes] = {}
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            parts = _safe_rel(name).parts
            members["/".join(parts[1:])] = z.read(name)
    if "start.sh" not in members:
        raise Fail(f"{zip_path} has no start.sh")

    first_run = not server_dir.is_dir()
    server_dir.mkdir(parents=True, exist_ok=True)
    if first_run:
        seed = _newest([d for d in env.user_home.glob("deepcharter-server-*") if d.is_dir() and d != server_dir])
        if seed is not None:
            for name in ("eula.txt", "whitelist.json", "ops.json", "server.properties", *(p.name for p in seed.glob("banned-*.json"))):
                if (seed / name).is_file():
                    shutil.copy2(seed / name, server_dir / name)

    mods = server_dir / "mods"
    mods.mkdir(exist_ok=True)
    old_lock = _lock_names(server_dir / "mods.lock")
    new_lock = _lock_names(members["mods.lock"].decode()) if "mods.lock" in members else set()
    ours = {name.removeprefix("mods/") for name in members if name.startswith("mods/") and name.endswith(".jar")}
    for jar in mods.glob("deepcharter-*.jar"):
        jar.unlink()
    for name in old_lock - new_lock:
        (mods / name).unlink(missing_ok=True)
    for name in ours:
        (mods / name).write_bytes(members[f"mods/{name}"])
    (server_dir / "start.sh").write_bytes(members["start.sh"])
    (server_dir / "start.sh").chmod(0o755)
    if "mods.lock" in members:
        (server_dir / "mods.lock").write_bytes(members["mods.lock"])
    warnings = [
        f"unknown jar mods/{jar.name}: start.sh refuses it; remove it"
        for jar in sorted(mods.glob("*.jar"))
        if jar.name not in new_lock and jar.name not in ours
    ]

    template = members.get("server.properties", b"").decode()
    properties = server_dir / "server.properties"
    if not properties.is_file():
        properties.write_text(template)
    else:
        have = properties.read_text()
        missing = [line for line in template.splitlines() if "=" in line and line.split("=", 1)[0].strip() not in _properties_keys(have)
                   and not line.lstrip().startswith("#")]
        if missing:
            properties.write_text(have + ("" if have.endswith("\n") or not have else "\n") + "\n".join(missing) + "\n")
    if "eula.txt" in members and not (server_dir / "eula.txt").is_file():
        (server_dir / "eula.txt").write_bytes(members["eula.txt"])

    if not _eula_accepted(server_dir):
        raise Fail(f"the server is installed but {server_dir / 'eula.txt'} is not eula=true: accepting the Minecraft EULA is the host's call", EXIT_EULA)
    return warnings


# ---------------------------------------------------------------- server runtime


def _log(server_dir: Path) -> Path:
    return server_dir / "logs" / "latest.log"


def _session_alive(env: Env) -> bool:
    return _call(env, ["tmux", "has-session", "-t", SESSION]).returncode == 0


def server_running(env: Env, server_dir: Path) -> bool:
    return _session_alive(env) or env.is_open(_log(server_dir))


class LogTail:
    """New complete lines of a log file, following it across a replacement (a restart) or truncation."""

    def __init__(self, path: Path):
        self.path = path
        self.inode = None
        self.offset = 0
        self.pending = b""
        if path.exists():
            info = path.stat()
            self.inode, self.offset = info.st_ino, info.st_size

    def poll(self) -> list[str]:
        try:
            info = self.path.stat()
        except FileNotFoundError:
            return []
        if info.st_ino != self.inode or info.st_size < self.offset:
            self.inode, self.offset, self.pending = info.st_ino, 0, b""
        with open(self.path, "rb") as f:
            f.seek(self.offset)
            data = f.read()
        self.offset += len(data)
        *lines, self.pending = (self.pending + data).split(b"\n")
        return [line.decode("utf-8", "replace").rstrip("\r") for line in lines]


def _addresses(env: Env, server_dir: Path) -> list[str]:
    port = "25565"
    properties = server_dir / "server.properties"
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith("server-port="):
                port = line.split("=", 1)[1].strip()
    hosts = ["localhost"]
    for cli in TAILSCALE:
        try:
            result = _call(env, [cli, "ip", "-4"])
        except Fail:
            continue
        address = (result.stdout or "").split("\n")[0].strip()
        if result.returncode == 0 and address:
            hosts.append(address)
            break
    return [f"{host}:{port}" for host in hosts]


def server_start(env: Env, server_dir: Path, wait: float) -> str:
    if not (server_dir / "start.sh").is_file():
        raise Fail(f"{server_dir} has no start.sh: run server-install first")
    if not _eula_accepted(server_dir):
        raise Fail(f"{server_dir / 'eula.txt'} is not eula=true", EXIT_EULA)
    if _session_alive(env):
        return "already running\nJoin: " + ", ".join(_addresses(env, server_dir))
    tail = LogTail(_log(server_dir))
    _run(env, ["tmux", "new-session", "-d", "-s", SESSION, "-c", str(server_dir), "./start.sh"])
    deadline = env.clock() + wait
    while True:
        for line in tail.poll():
            for event in classify(line, set()):
                if event.startswith("REFUSED "):
                    raise Fail(event, EXIT_REFUSED)
            parsed = parse_log_line(line)
            if parsed and parsed[1].startswith("Done ("):
                return "ready\nJoin: " + ", ".join(_addresses(env, server_dir))
        if not _session_alive(env):
            raise Fail(f"the server exited before it was ready: run {server_dir / 'start.sh'} in a terminal to see why", EXIT_EXITED)
        if env.clock() >= deadline:
            raise Fail(f"no 'Done (' in the log after {wait:g}s; the server may still be starting (tmux attach -t {SESSION})", EXIT_TIMEOUT)
        env.sleep(1)


def _send(env: Env, text: str) -> None:
    if not _session_alive(env):
        raise Fail("the server is not running")
    if any(ord(c) < 32 or ord(c) == 127 for c in text) or text.startswith("-"):
        raise Fail("refusing a command with control characters or a leading '-'")
    _run(env, ["tmux", "send-keys", "-t", SESSION, "-l", text])
    _run(env, ["tmux", "send-keys", "-t", SESSION, "Enter"])


def server_cmd(env: Env, server_dir: Path, command: str) -> list[str]:
    tail = LogTail(_log(server_dir))
    _send(env, command)
    env.sleep(2)
    return tail.poll()


def server_stop(env: Env) -> str:
    if not _session_alive(env):
        return "not running"
    _send(env, "stop")
    deadline = env.clock() + 120
    while _session_alive(env):
        if env.clock() >= deadline:
            raise Fail("the server did not stop within 120s (tmux attach -t deepcharter-server)", EXIT_TIMEOUT)
        env.sleep(1)
    _call(env, ["tmux", "kill-session", "-t", SESSION])
    return "stopped"


def server_status(env: Env, server_dir: Path) -> tuple[bool, list[str]]:
    if not _session_alive(env):
        return False, []
    for line in server_cmd(env, server_dir, "list"):
        parsed = parse_log_line(line)
        if parsed and "players online:" in parsed[1]:
            return True, [name.strip() for name in parsed[1].split("players online:", 1)[1].split(",") if name.strip()]
    return True, []


def ack(env: Env, player: str, issue: str, existing: bool) -> None:
    if not PLAYER.fullmatch(player):
        raise Fail(f"not a player name: {player!r}")
    if not ISSUE.fullmatch(issue):
        raise Fail(f"not an issue number: {issue!r}")
    verb = "Added to" if existing else "Logged as"
    text = {"text": f"[Deep Charter] Thanks! {verb} issue #{issue}.", "color": "gold"}
    _send(env, f"tellraw {player} {json.dumps(text)}")


# ---------------------------------------------------------------- log classification

LOG_LINE = re.compile(r"^\[[^\]]*\] \[[^\]/]*/(?P<level>[A-Z]+)\](?: \[[^\]]*\])?: (?P<msg>.*)$")
CHAT = re.compile(r"^(?:\[Not Secure\] )?<([A-Za-z0-9_]{3,16})> (.*)$")
FEEDBACK = re.compile(r"^!(?:feedback|fb)(?:\s+(.*))?$", re.IGNORECASE | re.DOTALL)
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
HEX = re.compile(r"\b0x[0-9a-fA-F]+\b")
NUMBER = re.compile(r"-?\d+(?:\.\d+)?")
REFUSAL = "Refusing to start this world"


def parse_log_line(line: str) -> tuple[str, str] | None:
    match = LOG_LINE.match(line)
    return (match.group("level"), match.group("msg")) if match else None


def _clean(text: str) -> str:
    kept = "".join(" " if unicodedata.category(c).startswith("C") else c for c in text)
    return " ".join(kept.split())[:FEEDBACK_CAP]


def classify(line: str, seen: set[str]) -> list[str]:
    """The watch events a log line produces. `seen` holds the WARN/ERROR signatures already reported this run."""
    parsed = parse_log_line(line)
    if parsed is None:
        return []
    level, msg = parsed
    if msg.startswith(REFUSAL):
        return [f"REFUSED {_clean(msg.partition(':')[2])}"]
    if level == "INFO":
        chat = CHAT.match(msg)
        feedback = FEEDBACK.match(chat.group(2)) if chat else None
        text = _clean(feedback.group(1) or "") if feedback else ""
        return [f"FEEDBACK {chat.group(1)} {text}"] if chat and text else []
    if level in ("WARN", "ERROR", "FATAL"):
        signature = NUMBER.sub("N", HEX.sub("H", UUID.sub("U", msg)))
        if signature in seen:
            return []
        seen.add(signature)
        return [f"{'WARN' if level == 'WARN' else 'ERROR'} {_clean(msg)}"]
    return []


def watch(env: Env, server_dir: Path, emit: Callable[[str], None]) -> None:
    """Follow the log from its current end until the server is gone; STOPPED is always the last event."""
    tail = LogTail(_log(server_dir))
    seen: set[str] = set()
    while True:
        alive = _session_alive(env)  # read before the log, so a crash's last lines are not lost
        for line in tail.poll():
            for event in classify(line, seen):
                emit(event)
        if not alive:
            emit("STOPPED")
            return
        env.sleep(1)


# ---------------------------------------------------------------- command line


def main(argv: list[str], env: Env) -> int:
    parser = argparse.ArgumentParser(prog="playtest.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    server = argparse.ArgumentParser(add_help=False)
    server.add_argument("--dir", type=Path, default=env.user_home / "deepcharter-server")
    sub.add_parser("build")
    sub.add_parser("client").add_argument("--mrpack", type=Path)
    sub.add_parser("server-install", parents=[server]).add_argument("--zip", type=Path)
    sub.add_parser("server-start", parents=[server]).add_argument("--wait", type=float, default=600)
    sub.add_parser("server-stop", parents=[server])
    sub.add_parser("server-status", parents=[server])
    sub.add_parser("server-cmd", parents=[server]).add_argument("words", nargs="+")
    sub.add_parser("server-watch", parents=[server])
    ack_parser = sub.add_parser("ack", parents=[server])
    ack_parser.add_argument("player")
    ack_parser.add_argument("issue")
    ack_parser.add_argument("--existing", action="store_true")
    args = parser.parse_args(argv)
    try:
        run_command(args, env)
    except Fail as failure:
        print(f"playtest: {failure.message}", file=sys.stderr)
        return failure.code
    return 0


def run_command(args: argparse.Namespace, env: Env) -> None:
    match args.command:
        case "build":
            mrpack, zip_path = build(env, REPO_ROOT)
            print(f"mrpack: {mrpack}\nserver zip: {zip_path}")
        case "client":
            result = client_install(env, args.mrpack)
            fov = "unset" if result.fov is None else f"{result.fov:g}"
            print(f"installed {result.version} into {result.instance}\nFOV: {fov}\nGUI scale: {result.gui_scale or 'unset'}")
        case "server-install":
            for warning in server_install(env, args.zip, args.dir):
                print(f"warning: {warning}")
            print(f"installed into {args.dir}")
        case "server-start":
            print(server_start(env, args.dir, args.wait))
        case "server-stop":
            print(server_stop(env))
        case "server-status":
            running, players = server_status(env, args.dir)
            print(f"running: {running}\nplayers: {', '.join(players) or 'none'}")
        case "server-cmd":
            print("\n".join(server_cmd(env, args.dir, " ".join(args.words))))
        case "server-watch":
            watch(env, args.dir, lambda event: print(event, flush=True))
        case "ack":
            ack(env, args.player, args.issue, args.existing)
            print("acknowledged")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], real_env()))
