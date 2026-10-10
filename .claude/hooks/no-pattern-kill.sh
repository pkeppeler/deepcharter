#!/bin/bash
# PreToolUse hook on Bash: refuse pattern kills. Several agents share this Mac, so `pkill -f gametest.sh`
# can kill another agent's run. Reads the tool input JSON on stdin; exit 2 plus a stderr message refuses.
#
# Refuses a command word of pkill or killall, `xargs kill`, and `kill` with a target that is not a plain
# PID, a shell variable ($pid, ${pid}) or an option (-9, -s TERM, --). Quoted text and arguments of other
# commands (grep pkill, git commit -m "...") pass, and so does heredoc text.
#
# Does not catch: a kill hidden in `bash -c '...'`, `eval`, `ssh`, a script file, or a variable holding the
# command; `kill $pid` where the variable was filled by pgrep; a kill after a line continuation; pkill
# spelled through a variable or alias. Unparsable input or command text is allowed: a false positive that
# blocks normal work costs more than a rare miss.
# shellcheck disable=SC2016  # the Python source is deliberately single-quoted: no shell expansion
exec python3 -I -c '
import json, re, shlex, sys

MSG = """Refused: pattern kills (pkill, killall, kill $(pgrep ...), kill %1, ... | xargs kill) can hit other agents on this Mac.
Record the PID when you start a process, then signal only that PID:
    cmd & pid=$!
    kill <pid>        # or: kill "$pid"
For Gradle, run ./gradlew --stop only for a daemon you started."""

OPS = {";", "&", "|", "&&", "||", "(", ")", ";;", "|&"}
WRAPPERS = {"sudo", "env", "command", "exec", "nohup", "time", "nice", "builtin"}


def refuse():
    sys.stderr.write(MSG + "\n")
    sys.exit(2)


def allow():
    sys.exit(0)


try:
    command = json.load(sys.stdin)["tool_input"]["command"]
    if not isinstance(command, str):
        allow()
    # Drop heredoc bodies, then make newlines command separators.
    command = re.sub(r"<<-?\s*([\x27\"]?)(\w+)\1[^\n]*\n.*?\n\s*\2(?=\n|$)", "<<", command, flags=re.S)
    lex = shlex.shlex(command.replace("\n", " ; "), posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    tokens = list(lex)
except Exception:
    allow()

PID = re.compile(r"^\d+$")
VAR = re.compile(r"^\$(\w+|\{\w+\})$")


def kill_ok(args):
    if args and args[0] in ("-l", "-L"):
        return True
    targets = 0
    i = 0
    while i < len(args):
        a = args[i]
        if a in ("-s", "-n"):
            i += 2
            continue
        if a == "--" or (a.startswith("-") and len(a) > 1):
            i += 1
            continue
        if not (PID.match(a) or VAR.match(a)):
            return False
        targets += 1
        i += 1
    return targets > 0


i = 0
n = len(tokens)
while i < n:
    # tokens[i] starts a command: skip assignments and wrappers.
    while i < n and tokens[i] not in OPS and (re.match(r"^\w+=", tokens[i]) or tokens[i] in WRAPPERS):
        i += 1
    if i < n and tokens[i] not in OPS:
        word = tokens[i].rsplit("/", 1)[-1]
        j = i + 1
        args = []
        while j < n and tokens[j] not in OPS:
            args.append(tokens[j])
            j += 1
        if word in ("pkill", "killall"):
            refuse()
        if word == "kill" and not kill_ok(args):
            refuse()
        if word == "xargs" and any(a.rsplit("/", 1)[-1] in ("kill", "pkill", "killall") for a in args):
            refuse()
        i = j
    while i < n and tokens[i] in OPS:
        i += 1
allow()
'
