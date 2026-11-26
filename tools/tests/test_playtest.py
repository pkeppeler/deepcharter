"""tools/playtest.py (#1400): client and server install, the log watcher, ack. No tmux, network or Prism: Env injects them."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import playtest as p  # noqa: E402

CDN = "https://cdn.example/"


def sha512(data: bytes) -> str:
    return hashlib.sha512(data).hexdigest()


class FakeSystem:
    """Stands in for subprocess, the downloader, lsof, pgrep and time. `script` runs one step per sleep."""

    def __init__(self):
        self.calls: list[list[str]] = []
        self.alive = False
        self.game_open = False
        self.prism = False
        self.tailscale_ip: str | None = None
        self.downloads: dict[str, bytes] = {}
        self.fetched: list[str] = []
        self.now = 0.0
        self.script: list = []
        self.on_new_session = None
        self.on_keys = None
        self.sent: list[str] = []

    def runner(self, argv, **_kwargs):
        self.calls.append(list(argv))
        out, rc = "", 0
        if argv[0] == "tmux":
            sub = argv[1]
            if sub == "has-session":
                rc = 0 if self.alive else 1
            elif sub == "new-session":
                self.alive = True
                if self.on_new_session:
                    self.on_new_session()
            elif sub == "send-keys" and "-l" in argv:
                self.sent.append(argv[-1])
                if self.on_keys:
                    self.on_keys(argv[-1])
            elif sub == "kill-session":
                self.alive = False
        elif argv[0].endswith("tailscale"):
            if self.tailscale_ip is None:
                raise FileNotFoundError(argv[0])
            out = self.tailscale_ip + "\n"
        return subprocess.CompletedProcess(argv, rc, out, "")

    def download(self, url):
        self.fetched.append(url)
        return self.downloads[url]

    def sleep(self, seconds):
        self.now += seconds
        if self.script:
            self.script.pop(0)()

    def env(self, root: Path) -> p.Env:
        return p.Env(
            home=root / "home", instances=root / "instances", user_home=root / "user",
            runner=self.runner, download=self.download, is_open=lambda path: self.game_open,
            prism_running=lambda: self.prism, sleep=self.sleep, clock=lambda: self.now,
        )


def make_mrpack(path: Path, version: str, files: dict[str, bytes], overrides: dict[str, bytes], mc="26.3", loader="0.19.5",
                env_client="required", scheme="https"):
    entries = [
        {"path": name, "hashes": {"sha512": sha512(data)}, "env": {"client": env_client, "server": "required"},
         "downloads": [f"{scheme}://cdn.example/{name}"], "fileSize": len(data)}
        for name, data in files.items()
    ]
    index = {"formatVersion": 1, "game": "minecraft", "versionId": version, "name": "Deep Charter friends build",
             "files": entries, "dependencies": {"minecraft": mc, "fabric-loader": loader}}
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("modrinth.index.json", json.dumps(index))
        for name, data in overrides.items():
            z.writestr(name, data)
    return path


def make_instance(root: Path, instance_id: str) -> Path:
    jar, mc, loader = "deepcharter-0.2.0.jar", "26.3", "0.19.5"
    d = root / instance_id
    (d / "minecraft" / "mods").mkdir(parents=True)
    (d / "minecraft" / "logs").mkdir()
    (d / "minecraft" / "saves" / "World1").mkdir(parents=True)
    (d / "minecraft" / "saves" / "World1" / "level.dat").write_text("level")
    (d / "minecraft" / "mods" / jar).write_text("old jar")
    (d / "minecraft" / "mods" / "sodium-old.jar").write_text("old sodium")
    (d / "minecraft" / "mods" / "usermod.jar").write_text("mine")
    (d / "minecraft" / "options.txt").write_text("fov:1.0\nguiScale:4\n")
    (d / "mrpack").mkdir()
    (d / "mrpack" / "overrides.txt").write_text(f"mods/{jar}\n")
    old = {"files": [{"path": "mods/sodium-old.jar"}]}
    (d / "mrpack" / "modrinth.index.json").write_text(json.dumps(old))
    (d / "instance.cfg").write_text(
        "[General]\nManagedPack=true\nManagedPackType=modrinth\nManagedPackName=Deep Charter friends build\n"
        f"name={instance_id}\nuuid=old-uuid\nJavaPath=/java\n")
    comps = [("net.minecraft", mc), ("net.fabricmc.intermediary", mc), ("net.fabricmc.fabric-loader", loader)]
    (d / "mmc-pack.json").write_text(json.dumps({"formatVersion": 1, "components": [
        {"uid": uid, "version": v, "cachedVersion": v} for uid, v in comps]}))
    return d


def cfg_value(path: Path, key: str) -> str | None:
    for line in path.read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1]
    return None


class PlaytestTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.sys = FakeSystem()
        self.env = self.sys.env(self.root)
        self.env.instances.mkdir()


class ClientInstall(PlaytestTestCase):
    def pack(self, version="0.2.0-aaaaaaa", **kw):
        jar = "mods/deepcharter-0.2.0.jar"
        files = kw.pop("files", {"mods/sodium-new.jar": b"new sodium"})
        for name, data in files.items():
            self.sys.downloads[f"{CDN}{name}"] = data
        return make_mrpack(self.root / f"{version}.mrpack", version, files, {f"overrides/{jar}": b"new deepcharter"}, **kw)

    def test_first_run_copies_the_newest_instance_and_installs_in_place(self):
        older = make_instance(self.env.instances, "deepcharter-friends-0.2.0-cb4ed19")
        newer = make_instance(self.env.instances, "deepcharter-friends-0.2.0-9cb6091")
        os.utime(older, (1, 1))
        os.utime(newer, (2, 2))
        (newer / "minecraft" / "options.txt").write_text("fov:0.5\nguiScale:3\n")
        result = p.client_install(self.env, self.pack())
        target = self.env.instances / "deepcharter"
        mods = target / "minecraft" / "mods"
        self.assertEqual(cfg_value(target / "instance.cfg", "name"), "Deep Charter")
        self.assertRegex(cfg_value(target / "instance.cfg", "uuid"), r"^[0-9a-f]{32}$")
        self.assertEqual(cfg_value(target / "instance.cfg", "ManagedPackVersionName"), "0.2.0-aaaaaaa")
        self.assertEqual(cfg_value(target / "instance.cfg", "JavaPath"), "/java")
        self.assertEqual((mods / "deepcharter-0.2.0.jar").read_text(), "new deepcharter")
        self.assertEqual((mods / "sodium-new.jar").read_text(), "new sodium")
        self.assertFalse((mods / "sodium-old.jar").exists())
        self.assertEqual((mods / "usermod.jar").read_text(), "mine")
        self.assertEqual((target / "minecraft" / "saves" / "World1" / "level.dat").read_text(), "level")
        self.assertEqual((target / "minecraft" / "options.txt").read_text(), "fov:0.5\nguiScale:3\n")
        self.assertEqual((target / "mrpack" / "overrides.txt").read_text().split(), ["mods/deepcharter-0.2.0.jar"])
        index = json.loads((target / "mrpack" / "modrinth.index.json").read_text())
        self.assertEqual(index["versionId"], "0.2.0-aaaaaaa")
        self.assertEqual((result.fov, result.gui_scale), (90.0, "3"))
        self.assertTrue((older / "minecraft" / "mods" / "sodium-old.jar").exists(), "the source instances are untouched")
        self.assertTrue((newer / "minecraft" / "mods" / "sodium-old.jar").exists())

    def test_second_run_updates_in_place_and_keeps_user_state(self):
        make_instance(self.env.instances, "deepcharter")
        p.client_install(self.env, self.pack("0.2.0-bbbbbbb"))
        target = self.env.instances / "deepcharter"
        (target / "minecraft" / "options.txt").write_text("fov:1.0\nguiScale:4\nkey_x:y\n")
        p.client_install(self.env, self.pack("0.2.0-ccccccc"))
        self.assertIn("key_x:y", (target / "minecraft" / "options.txt").read_text())
        self.assertEqual(cfg_value(target / "instance.cfg", "ManagedPackVersionName"), "0.2.0-ccccccc")
        self.assertTrue((target / "minecraft" / "mods" / "usermod.jar").exists())

    def test_no_instance_to_seed_from_is_an_error(self):
        with self.assertRaises(p.Fail) as ctx:
            p.client_install(self.env, self.pack())
        self.assertIn("Import", ctx.exception.message)

    def test_refuses_while_the_game_runs(self):
        make_instance(self.env.instances, "deepcharter")
        self.sys.game_open = True
        with self.assertRaises(p.Fail) as ctx:
            p.client_install(self.env, self.pack())
        self.assertIn("running", ctx.exception.message)
        self.assertTrue((self.env.instances / "deepcharter" / "minecraft" / "mods" / "sodium-old.jar").exists())

    def test_http_download_is_refused(self):
        make_instance(self.env.instances, "deepcharter")
        with self.assertRaises(p.Fail) as ctx:
            p.client_install(self.env, self.pack(scheme="http"))
        self.assertIn("https", ctx.exception.message)
        self.assertEqual(self.sys.fetched, [])

    def test_hash_mismatch_leaves_the_instance_untouched(self):
        make_instance(self.env.instances, "deepcharter")
        mrpack = self.pack()
        self.sys.downloads[f"{CDN}mods/sodium-new.jar"] = b"tampered"
        with self.assertRaises(p.Fail) as ctx:
            p.client_install(self.env, mrpack)
        self.assertIn("sha512", ctx.exception.message)
        self.assertTrue((self.env.instances / "deepcharter" / "minecraft" / "mods" / "sodium-old.jar").exists())

    def test_downloads_are_cached(self):
        make_instance(self.env.instances, "deepcharter")
        p.client_install(self.env, self.pack("0.2.0-bbbbbbb"))
        p.client_install(self.env, self.pack("0.2.0-ccccccc"))
        self.assertEqual(self.sys.fetched, [f"{CDN}mods/sodium-new.jar"])

    def test_server_only_files_are_skipped(self):
        make_instance(self.env.instances, "deepcharter")
        p.client_install(self.env, self.pack(env_client="unsupported"))
        self.assertEqual(self.sys.fetched, [])
        self.assertFalse((self.env.instances / "deepcharter" / "minecraft" / "mods" / "sodium-new.jar").exists())

    def test_path_escaping_the_game_dir_is_refused(self):
        make_instance(self.env.instances, "deepcharter")
        with self.assertRaises(p.Fail):
            p.client_install(self.env, self.pack(files={"../evil.jar": b"x"}))

    def test_loader_change_updates_mmc_pack_when_prism_is_closed(self):
        make_instance(self.env.instances, "deepcharter")
        p.client_install(self.env, self.pack(mc="26.4", loader="0.20.0"))
        comps = {c["uid"]: c for c in json.loads((self.env.instances / "deepcharter" / "mmc-pack.json").read_text())["components"]}
        self.assertEqual(comps["net.minecraft"]["version"], "26.4")
        self.assertEqual(comps["net.fabricmc.intermediary"]["cachedVersion"], "26.4")
        self.assertEqual(comps["net.fabricmc.fabric-loader"]["version"], "0.20.0")

    def test_loader_change_with_prism_open_asks_to_quit_it(self):
        make_instance(self.env.instances, "deepcharter")
        self.sys.prism = True
        with self.assertRaises(p.Fail) as ctx:
            p.client_install(self.env, self.pack(mc="26.4"))
        self.assertIn("Quit Prism", ctx.exception.message)
        self.assertTrue((self.env.instances / "deepcharter" / "minecraft" / "mods" / "sodium-old.jar").exists())

    def test_same_versions_install_with_prism_open(self):
        make_instance(self.env.instances, "deepcharter")
        self.sys.prism = True
        p.client_install(self.env, self.pack())

    def victim(self) -> Path:
        path = self.env.instances / "victim.txt"
        path.write_text("keep me")
        return path

    def assert_untouched(self, victim: Path):
        self.assertEqual(victim.read_text(), "keep me")
        target = self.env.instances / "deepcharter"
        self.assertIsNone(cfg_value(target / "instance.cfg", "ManagedPackVersionName"))
        self.assertTrue((target / "minecraft" / "mods" / "sodium-old.jar").exists())

    def test_a_tampered_stored_index_cannot_delete_outside_the_game_dir(self):
        target = make_instance(self.env.instances, "deepcharter")
        victim = self.victim()
        for evil in ("../../victim.txt", str(victim)):
            with self.subTest(evil):
                (target / "mrpack" / "modrinth.index.json").write_text(json.dumps({"files": [{"path": evil}]}))
                with self.assertRaises(p.Fail):
                    p.client_install(self.env, self.pack())
                self.assertEqual(victim.read_text(), "keep me")
                self.assertIsNone(cfg_value(target / "instance.cfg", "ManagedPackVersionName"))

    def test_a_tampered_stored_overrides_list_cannot_delete_outside_the_game_dir(self):
        target = make_instance(self.env.instances, "deepcharter")
        victim = self.victim()
        for evil in ("../../victim.txt", str(victim)):
            with self.subTest(evil):
                (target / "mrpack" / "overrides.txt").write_text(f"mods/deepcharter-0.2.0.jar\n{evil}\n")
                with self.assertRaises(p.Fail):
                    p.client_install(self.env, self.pack())
                self.assert_untouched(victim)

    def test_a_symlinked_mods_dir_cannot_be_deleted_through_or_written_to(self):
        target = make_instance(self.env.instances, "deepcharter")
        victim = self.victim()
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "sodium-old.jar").write_text("outside")
        shutil.rmtree(target / "minecraft" / "mods")
        (target / "minecraft" / "mods").symlink_to(outside)
        with self.assertRaises(p.Fail):
            p.client_install(self.env, self.pack())
        self.assertEqual((outside / "sodium-old.jar").read_text(), "outside")
        self.assertEqual(victim.read_text(), "keep me")
        self.assertIsNone(cfg_value(target / "instance.cfg", "ManagedPackVersionName"))

    def test_a_symlinked_dir_in_the_seed_leaves_no_half_made_instance(self):
        seed = make_instance(self.env.instances, "deepcharter-friends-0.2.0-aaaaaaa")
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "sodium-old.jar").write_text("outside")
        shutil.rmtree(seed / "minecraft" / "mods")
        (seed / "minecraft" / "mods").symlink_to(outside)
        with self.assertRaises(p.Fail):
            p.client_install(self.env, self.pack())
        self.assertFalse((self.env.instances / "deepcharter").exists())
        self.assertEqual((outside / "sodium-old.jar").read_text(), "outside")

    def test_a_listed_directory_is_refused_before_any_write(self):
        target = make_instance(self.env.instances, "deepcharter")
        (target / "mrpack" / "overrides.txt").write_text("saves\n")
        with self.assertRaises(p.Fail):
            p.client_install(self.env, self.pack())
        self.assertTrue((target / "minecraft" / "saves" / "World1" / "level.dat").exists())
        self.assertIsNone(cfg_value(target / "instance.cfg", "ManagedPackVersionName"))

    def test_options_txt_is_never_written_or_deleted_by_a_pack(self):
        target = make_instance(self.env.instances, "deepcharter")
        (target / "mrpack" / "overrides.txt").write_text("options.txt\n")
        mrpack = make_mrpack(self.root / "o.mrpack", "0.2.0-ddddddd", {}, {"overrides/options.txt": b"fov:0.0\n", "client-overrides/options.txt": b"fov:0.0\n"})
        p.client_install(self.env, mrpack)
        self.assertEqual((target / "minecraft" / "options.txt").read_text(), "fov:1.0\nguiScale:4\n")
        self.assertNotIn("options.txt", (target / "mrpack" / "overrides.txt").read_text())

    def test_mmc_pack_is_left_alone_when_versions_match(self):
        target = make_instance(self.env.instances, "deepcharter")
        before = (target / "mmc-pack.json").read_bytes()
        self.sys.prism = True
        p.client_install(self.env, self.pack())
        self.assertEqual((target / "mmc-pack.json").read_bytes(), before)

    def test_a_missing_lsof_is_a_clear_error(self):
        path = self.root / "latest.log"
        path.write_text("x")
        with mock.patch.object(p.subprocess, "run", side_effect=FileNotFoundError("lsof")), self.assertRaises(p.Fail) as ctx:
            p.real_env().is_open(path)
        self.assertIn("lsof", ctx.exception.message)

    def test_fov_is_degrees(self):
        self.assertEqual(p.fov_degrees("1.0"), 110.0)
        self.assertEqual(p.fov_degrees("0.0"), 70.0)


def make_server_zip(path: Path, version: str, lock: str, jars: dict[str, bytes]) -> Path:
    props = "server-port=25565\nnew-key=1\n"
    top = f"deepcharter-server-{version}"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(f"{top}/start.sh", f"#!/bin/sh\n# {version}\n")
        z.writestr(f"{top}/mods.lock", lock)
        z.writestr(f"{top}/server.properties", props)
        z.writestr(f"{top}/eula.txt", "eula=false\n")
        for name, data in jars.items():
            z.writestr(f"{top}/mods/{name}", data)
    return path


class ServerInstall(PlaytestTestCase):
    def old_server(self, name, mtime, eula="eula=true\n"):
        d = self.env.user_home / name
        (d / "world").mkdir(parents=True)
        (d / "world" / "level.dat").write_text("w")
        (d / "whitelist.json").write_text('[{"name":"Abc"}]')
        (d / "ops.json").write_text("[]")
        (d / "banned-players.json").write_text("[]")
        (d / "eula.txt").write_text(eula)
        (d / "server.properties").write_text("server-port=25570\n")
        os.utime(d, (mtime, mtime))
        return d

    def zip(self, version="0.2.0-aaaaaaa", lock="h lithium-new.jar https://x/l.jar\n", jars=None):
        jars = jars if jars is not None else {"deepcharter-0.2.0.jar": b"new"}
        return make_server_zip(self.root / f"{version}.zip", version, lock, jars)

    def test_first_run_seeds_from_the_newest_server_dir_without_a_world(self):
        self.old_server("deepcharter-server-0.2.0-e3bc0fd", 1, eula="eula=false\n")
        self.old_server("deepcharter-server-0.2.0-9cb6091", 2)
        target = self.root / "srv"
        warnings = p.server_install(self.env, self.zip(), target)
        self.assertEqual(warnings, [])
        self.assertEqual((target / "whitelist.json").read_text(), '[{"name":"Abc"}]')
        self.assertEqual((target / "eula.txt").read_text(), "eula=true\n")
        self.assertIn("server-port=25570", (target / "server.properties").read_text())
        self.assertIn("new-key=1", (target / "server.properties").read_text())
        self.assertFalse((target / "world").exists())
        self.assertEqual((target / "mods" / "deepcharter-0.2.0.jar").read_bytes(), b"new")
        self.assertTrue(os.access(target / "start.sh", os.X_OK))

    def test_later_run_replaces_ours_prunes_the_old_lock_and_keeps_the_rest(self):
        target = self.root / "srv"
        (target / "mods").mkdir(parents=True)
        (target / "mods.lock").write_text("h lithium-old.jar https://x/o.jar\n")
        (target / "mods" / "lithium-old.jar").write_text("o")
        (target / "mods" / "deepcharter-0.1.0.jar").write_text("ancient")
        (target / "mods" / "stranger.jar").write_text("s")
        (target / "world").mkdir()
        (target / "world" / "level.dat").write_text("w")
        (target / "eula.txt").write_text("eula=true\n")
        (target / "server.properties").write_text("server-port=1\n")
        warnings = p.server_install(self.env, self.zip(), target)
        mods = sorted(f.name for f in (target / "mods").iterdir())
        self.assertEqual(mods, ["deepcharter-0.2.0.jar", "stranger.jar"])
        self.assertEqual(len(warnings), 1)
        self.assertIn("stranger.jar", warnings[0])
        self.assertEqual((target / "world" / "level.dat").read_text(), "w")
        self.assertEqual((target / "server.properties").read_text(), "server-port=1\nnew-key=1\n")
        self.assertIn("lithium-new.jar", (target / "mods.lock").read_text())

    def test_refuses_while_the_server_runs(self):
        self.sys.alive = True
        with self.assertRaises(p.Fail) as ctx:
            p.server_install(self.env, self.zip(), self.root / "srv")
        self.assertIn("running", ctx.exception.message)
        self.assertFalse((self.root / "srv").exists())

    def test_eula_not_accepted_exits_with_its_own_code_after_installing(self):
        target = self.root / "srv"
        with self.assertRaises(p.Fail) as ctx:
            p.server_install(self.env, self.zip(), target)
        self.assertEqual(ctx.exception.code, p.EXIT_EULA)
        self.assertTrue((target / "start.sh").exists())

    def test_a_stored_lock_name_that_escapes_mods_deletes_nothing(self):
        target = self.root / "srv"
        (target / "mods").mkdir(parents=True)
        victim = self.root / "victim.txt"
        victim.write_text("keep me")
        for evil in ("../../victim.txt", str(victim), "sub/x.jar"):
            with self.subTest(evil):
                (target / "mods.lock").write_text(f"h {evil} https://x/o.jar\n")
                with self.assertRaises(p.Fail):
                    p.server_install(self.env, self.zip(), target)
                self.assertEqual(victim.read_text(), "keep me")
                self.assertFalse((target / "start.sh").exists())

    def test_a_new_lock_name_that_escapes_mods_is_refused(self):
        with self.assertRaises(p.Fail):
            p.server_install(self.env, self.zip(lock="h ../x.jar https://x/o.jar\n"), self.root / "srv")
        self.assertFalse((self.root / "srv" / "start.sh").exists())

    def test_a_nested_mods_entry_in_the_zip_is_refused_cleanly(self):
        with self.assertRaises(p.Fail):
            p.server_install(self.env, self.zip(jars={"sub/x.jar": b"x"}), self.root / "srv")
        self.assertFalse((self.root / "srv").exists())

    def test_a_user_added_jar_survives_and_is_reported(self):
        target = self.root / "srv"
        (target / "mods").mkdir(parents=True)
        (target / "mods" / "mine.jar").write_text("m")
        (target / "eula.txt").write_text("eula=true\n")
        warnings = p.server_install(self.env, self.zip(), target)
        self.assertEqual((target / "mods" / "mine.jar").read_text(), "m")
        self.assertEqual(len(warnings), 1)
        self.assertIn("mine.jar", warnings[0])

    def test_zip_member_escaping_the_dir_is_refused(self):
        bad = self.root / "bad.zip"
        with zipfile.ZipFile(bad, "w") as z:
            z.writestr("deepcharter-server-x/../../evil", "x")
        with self.assertRaises(p.Fail):
            p.server_install(self.env, bad, self.root / "srv")


LOG = "logs/latest.log"


class Runtime(PlaytestTestCase):
    def setUp(self):
        super().setUp()
        self.srv = self.root / "srv"
        (self.srv / "logs").mkdir(parents=True)
        (self.srv / "start.sh").write_text("#!/bin/sh\n")
        (self.srv / "eula.txt").write_text("eula=true\n")
        self.log = self.srv / LOG

    def write(self, *lines, mode="a"):
        with open(self.log, mode) as f:
            for line in lines:
                f.write(line + "\n")

    def test_start_waits_for_done_and_prints_addresses(self):
        self.sys.tailscale_ip = "100.1.2.3"
        self.sys.on_new_session = lambda: self.write("[10:00:00] [Server thread/INFO]: Done (3.2s)! For help, type \"help\"")
        out = p.server_start(self.env, self.srv, wait=60)
        new = [c for c in self.sys.calls if c[:2] == ["tmux", "new-session"]][0]
        self.assertEqual(new[2:7], ["-d", "-s", "deepcharter-server", "-c", str(self.srv)])
        self.assertEqual(new[-1], "./start.sh")
        self.assertIn("localhost", out)
        self.assertIn("100.1.2.3", out)

    def test_start_reads_a_replaced_log_from_the_top(self):
        self.write("[09:00:00] [Server thread/INFO]: Done (1s)! old run")
        self.sys.on_new_session = lambda: self.write("[10:00:00] [Server thread/INFO]: Done (3s)!", mode="w")
        self.log.rename(self.srv / "logs" / "old.log")
        p.server_start(self.env, self.srv, wait=60)

    def test_start_reports_a_refused_world(self):
        self.sys.on_new_session = lambda: self.write(
            "[10:00:00] [main/ERROR]: Refusing to start this world: saved in format 3, this build reads 5")
        with self.assertRaises(p.Fail) as ctx:
            p.server_start(self.env, self.srv, wait=60)
        self.assertEqual(ctx.exception.code, p.EXIT_REFUSED)
        self.assertIn("format 3", ctx.exception.message)

    def test_start_reports_a_process_that_exits(self):
        def die():
            self.sys.alive = False
        self.sys.on_new_session = lambda: self.sys.script.append(die)
        with self.assertRaises(p.Fail) as ctx:
            p.server_start(self.env, self.srv, wait=60)
        self.assertEqual(ctx.exception.code, p.EXIT_EXITED)

    def test_start_times_out(self):
        with self.assertRaises(p.Fail) as ctx:
            p.server_start(self.env, self.srv, wait=5)
        self.assertEqual(ctx.exception.code, p.EXIT_TIMEOUT)

    def test_start_without_the_eula_is_refused(self):
        (self.srv / "eula.txt").write_text("eula=false\n")
        with self.assertRaises(p.Fail) as ctx:
            p.server_start(self.env, self.srv, wait=5)
        self.assertEqual(ctx.exception.code, p.EXIT_EULA)

    def test_cmd_sends_literally_then_enter_and_returns_new_log_lines(self):
        self.sys.alive = True
        self.write("[10:00:00] [Server thread/INFO]: before")
        self.sys.on_keys = lambda text: self.write("[10:00:01] [Server thread/INFO]: There are 0 of a max of 20 players online:")
        lines = p.server_cmd(self.env, self.srv, "list")
        self.assertEqual(lines, ["[10:00:01] [Server thread/INFO]: There are 0 of a max of 20 players online:"])
        sends = [c for c in self.sys.calls if c[:2] == ["tmux", "send-keys"]]
        self.assertEqual(sends[0], ["tmux", "send-keys", "-t", "deepcharter-server", "-l", "--", "list"])
        self.assertEqual(sends[1], ["tmux", "send-keys", "-t", "deepcharter-server", "Enter"])

    def test_cmd_rejects_a_multi_line_command_and_a_stopped_server(self):
        with self.assertRaises(p.Fail):
            p.server_cmd(self.env, self.srv, "list")
        self.sys.alive = True
        with self.assertRaises(p.Fail):
            p.server_cmd(self.env, self.srv, "say hi\nstop")

    def test_cmd_text_ending_in_a_semicolon_is_escaped_for_tmux(self):
        self.sys.alive = True
        p.server_cmd(self.env, self.srv, "say hi;")
        self.assertEqual(self.sys.sent, ["say hi\\;"])

    def test_an_error_is_not_swallowed_by_an_earlier_warn_with_the_same_text(self):
        seen: set[str] = set()
        self.assertEqual(len(p.classify("[10:00:00] [Server thread/WARN]: Boom 1", seen)), 1)
        [event] = p.classify("[10:00:01] [Server thread/ERROR]: Boom 2", seen)
        self.assertTrue(event.startswith("ERROR "))

    def test_stop_sends_stop_and_waits_for_the_exit(self):
        self.sys.alive = True

        def die():
            self.sys.alive = False
        self.sys.script = [lambda: None, die]
        p.server_stop(self.env)
        self.assertEqual(self.sys.sent, ["stop"])

    def test_status_lists_players(self):
        self.sys.alive = True
        self.sys.on_keys = lambda text: self.write(
            "[10:00:01] [Server thread/INFO]: There are 2 of a max of 20 players online: Abc, Def_1")
        self.assertEqual(p.server_status(self.env, self.srv), (True, ["Abc", "Def_1"]))
        self.sys.alive = False
        self.assertEqual(p.server_status(self.env, self.srv), (False, []))

    def test_ack_text_is_fixed_and_validated(self):
        self.sys.alive = True
        p.ack(self.env, "Meat_Cups", "42", "Pod is slow", existing=False)
        p.ack(self.env, "Meat_Cups", "42", "Pod is slow", existing=True)
        first, second = (json.loads(s.split(" ", 2)[2]) for s in self.sys.sent)
        self.assertEqual(self.sys.sent[0].split(" ")[:2], ["tellraw", "Meat_Cups"])
        self.assertEqual(first, {"text": "[Deep Charter] Thanks! Logged as issue #42: Pod is slow.", "color": "gold"})
        self.assertEqual(second["text"], "[Deep Charter] Thanks! Added to issue #42: Pod is slow.")
        for player, issue in (("a b", "1"), ("ab", "1"), ("x" * 17, "1"), ("Abc; stop", "1"), ("Abc", "x"), ("Abc", "0"), ("Abc", "-1"), ("Abc", "1 2")):
            with self.subTest(player=player, issue=issue), self.assertRaises(p.Fail):
                p.ack(self.env, player, issue, "Pod is slow", existing=False)
        self.assertEqual(len(self.sys.sent), 2)

    def ack_text(self, title):
        self.sys.alive = True
        self.sys.sent.clear()
        p.ack(self.env, "Abc", "7", title, existing=False)
        return json.loads(self.sys.sent[0].split(" ", 2)[2])["text"]

    def test_ack_title_is_validated(self):
        self.sys.alive = True
        for title in ("", "   ", "\t", "two\nlines", "bell\x07", "del\x7f"):
            with self.subTest(title=title), self.assertRaises(p.Fail):
                p.ack(self.env, "Abc", "7", title, existing=False)
        self.assertEqual(self.sys.sent, [])

    def test_ack_title_punctuation_and_quotes(self):
        prefix = "[Deep Charter] Thanks! Logged as issue #7: "
        for title, shown in (("Pod is slow.", "Pod is slow."), ("Why slow?", "Why slow?"), ("Fix it!", "Fix it!"),
                             ("  padded  ", "padded."), ('Say "hi" \\ there', 'Say "hi" \\ there.')):
            with self.subTest(title=title):
                self.assertEqual(self.ack_text(title), prefix + shown)

    def test_ack_long_title_is_cut_with_an_ellipsis(self):
        text = self.ack_text("word " * 40)
        title = text.removeprefix("[Deep Charter] Thanks! Logged as issue #7: ")
        self.assertEqual(len(title), p.ACK_TITLE_MAX)
        self.assertTrue(title.endswith("\u2026"))
        exact = self.ack_text("x" * p.ACK_TITLE_MAX)
        self.assertTrue(exact.endswith("x."))
        self.assertNotIn("\u2026", exact)


class Classify(unittest.TestCase):
    def events(self, line, seen=None):
        return p.classify(line, set() if seen is None else seen)

    def test_feedback_chat(self):
        for text in ("!feedback the pod is slow", "!FB the pod is slow", "!Feedback  the pod is slow "):
            with self.subTest(text):
                self.assertEqual(self.events(f"[23:19:52] [Server thread/INFO]: <MeatCups> {text}"), ["FEEDBACK MeatCups the pod is slow"])
        self.assertEqual(self.events("[23:19:52] [Server thread/INFO]: [Not Secure] <MeatCups> !fb hi"), ["FEEDBACK MeatCups hi"])

    def test_not_feedback(self):
        for line in (
            "[23:19:52] [Server thread/INFO]: <MeatCups> hello",
            "[23:19:52] [Server thread/INFO]: <MeatCups> !feedbackx nope",
            "[23:19:52] [Server thread/INFO]: <MeatCups> !feedback",
            "[23:19:52] [Server thread/INFO]: <a> !fb short name",
            "[23:19:52] [Server thread/INFO]: <bad name!> !fb odd name",
            "[23:19:52] [Server thread/INFO]: <Abc> <Def> !fb nested",
            "\tat net.minecraft.Foo.bar(Foo.java:1)",
        ):
            with self.subTest(line):
                self.assertEqual(self.events(line), [])

    def test_feedback_text_is_sanitised_and_capped(self):
        [event] = self.events("[23:19:52] [Server thread/INFO]: <MeatCups> !fb a\x1b[31m\x07b‮c" + "z" * 400)
        text = event.split(" ", 2)[2]
        self.assertNotIn("\x1b", text)
        self.assertNotIn("\x07", text)
        self.assertNotIn("‮", text)
        self.assertLessEqual(len(text), 300)

    def test_warn_and_error_dedupe_by_normalised_signature(self):
        seen: set[str] = set()
        a = self.events("[10:00:00] [Server thread/WARN]: Can't keep up! Running 2031ms behind, skipping 40 tick(s)", seen)
        b = self.events("[10:00:09] [Server thread/WARN]: Can't keep up! Running 99ms behind, skipping 2 tick(s)", seen)
        c = self.events("[10:00:10] [Server thread/ERROR]: Failed at 10, 64, -20 chunk 0xdeadbeef", seen)
        d = self.events("[10:00:11] [Server thread/ERROR]: Failed at 11, 70, -21 chunk 0xcafe1234", seen)
        self.assertEqual(len(a), 1)
        self.assertTrue(a[0].startswith("WARN "))
        self.assertEqual(b, [])
        self.assertTrue(c[0].startswith("ERROR "))
        self.assertEqual(d, [])

    def test_info_lines_are_ignored_and_a_refusal_is_its_own_event(self):
        self.assertEqual(self.events("[10:00:00] [Server thread/INFO]: Preparing spawn area"), [])
        self.assertEqual(self.events("[10:00:00] [main/ERROR]: Refusing to start this world: saved in format 3"),
                         ["REFUSED saved in format 3"])

    def test_logger_prefix_is_accepted(self):
        self.assertEqual(self.events("[10:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: <Abc> !fb hi"), ["FEEDBACK Abc hi"])


class Watch(PlaytestTestCase):
    def setUp(self):
        super().setUp()
        self.srv = self.root / "srv"
        (self.srv / "logs").mkdir(parents=True)
        self.log = self.srv / LOG
        self.log.write_text("[09:00:00] [Server thread/INFO]: <Old> !fb from before the watch\n")
        self.out: list[str] = []

    def append(self, *lines, mode="a"):
        with open(self.log, mode) as f:
            f.write("".join(line + "\n" for line in lines))

    def append_raw(self, text):
        with open(self.log, "a") as f:
            f.write(text)

    def test_follows_from_the_end_across_a_restart_and_reports_stop(self):
        self.sys.alive = True
        self.sys.script = [
            lambda: self.append("[10:00:00] [Server thread/INFO]: <Abc> !fb first", "[10:00:01] [Server thread/WARN]: Slow 5"),
            lambda: self.append("[10:00:02] [Server thread/WARN]: Slow 6"),
            lambda: (self.log.rename(self.srv / "logs" / "old.log"), self.append("[11:00:00] [Server thread/INFO]: <Def> !fb second")),
            lambda: setattr(self.sys, "alive", False),
        ]
        p.watch(self.env, self.srv, self.out.append)
        self.assertEqual(self.out, ["FEEDBACK Abc first", "WARN Slow 5", "FEEDBACK Def second", "STOPPED"])

    def test_not_running_is_reported_at_once(self):
        p.watch(self.env, self.srv, self.out.append)
        self.assertEqual(self.out, ["STOPPED"])

    def test_a_partial_line_waits_for_its_newline(self):
        self.sys.alive = True
        half = "[10:00:00] [Server thread/INFO]: <Abc> !fb hal"
        self.sys.script = [
            lambda: self.append_raw(half),
            lambda: self.append_raw("f\n"),
            lambda: setattr(self.sys, "alive", False),
        ]
        p.watch(self.env, self.srv, self.out.append)
        self.assertEqual(self.out, ["FEEDBACK Abc half", "STOPPED"])


class Build(PlaytestTestCase):
    def test_build_fetches_pins_the_worktree_and_packages_with_the_jar(self):
        repo = self.root / "repo"
        repo.mkdir()
        tree = self.env.home / "build"

        git_envs = []

        def runner(argv, **kwargs):
            self.sys.calls.append(list(argv))
            if argv[0] == "git":
                git_envs.append(kwargs["env"])
            if argv[:3] == ["git", "-C", str(tree)] and argv[3:5] == ["rev-parse", "--short=7"]:
                return subprocess.CompletedProcess(argv, 0, "abc1234\n", "")
            if argv[0] == "./gradlew":
                (tree / "build" / "libs" / "deepcharter-0.2.0.jar").write_text("jar")
            if "worktree" in argv:
                (tree / "build" / "dist").mkdir(parents=True)
                (tree / "build" / "libs").mkdir(parents=True)
                (tree / "gradle.properties").write_text("version=0.2.0\n")
                (tree / "build" / "dist" / "deepcharter-friends-0.2.0-abc1234.mrpack").write_text("m")
                (tree / "build" / "dist" / "deepcharter-server-0.2.0-abc1234.zip").write_text("z")
            return subprocess.CompletedProcess(argv, 0, "", "")

        env = self.sys.env(self.root)
        env = p.Env(**{**env.__dict__, "runner": runner})
        with mock.patch.dict(os.environ, {"GIT_DIR": "/nonexistent"}):
            mrpack, zip_path = p.build(env, repo)
        self.assertTrue(git_envs and all("GIT_DIR" not in e for e in git_envs), "git_env: a hook's GIT_DIR must not reach git")
        self.assertEqual(mrpack.name, "deepcharter-friends-0.2.0-abc1234.mrpack")
        self.assertEqual(zip_path.name, "deepcharter-server-0.2.0-abc1234.zip")
        calls = self.sys.calls
        self.assertEqual(calls[0], ["git", "-C", str(repo), "fetch", "origin", "main"])
        self.assertIn(["git", "-C", str(repo), "worktree", "add", "--detach", str(tree), "origin/main"], calls)
        self.assertIn(["./gradlew", "jar", "-q"], calls)
        self.assertIn(["tools/package-friends-build.sh", "0.2.0-abc1234"], calls)


if __name__ == "__main__":
    unittest.main()
