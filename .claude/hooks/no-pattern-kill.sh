#!/bin/bash
# PreToolUse hook on Bash: refuse pattern kills. Several agents share this Mac, so `pkill -f gametest.sh`
# can kill another agent's run. Reads the tool input JSON on stdin; exit 2 plus a stderr message refuses.
#
# Refuses a command word of pkill or killall, `xargs kill`, and `kill` whose target is not a plain PID
# (123, -123 for a group), a single parameter expansion ($pid, $!, ${pid:-}, "${PIDS[@]}") or the pidfile
# idiom "$(cat <one path>)". Options (-9, -s TERM, --) and redirections (2>/dev/null) are ignored. The
# command word is found after ; & | && || ( ) newlines and the keywords if/then/do/elif/else/while/until/{/!.
# Quoted text, comments, heredoc bodies and arguments of other commands (grep pkill, git commit -m "...")
# pass.
#
# Does not catch: a kill hidden in `bash -c '...'`, `eval`, `ssh`, a script file, a backtick or $(...)
# substitution inside double quotes (other than the pidfile idiom), or a variable holding the command;
# `kill $pid` where the variable was filled by pgrep; pkill spelled through a variable or alias; `case`
# arms. Unparsable input or command text is allowed (so is an unterminated heredoc, whose body is
# ignored): a false positive that blocks normal work costs more than a rare miss. No python3: allowed.
command -v python3 >/dev/null || exit 0
# shellcheck disable=SC2016  # the Python source is deliberately single-quoted: no shell expansion
exec python3 -I -c '
import json, re, sys

MSG = """Refused: pattern kills (pkill, killall, kill $(pgrep ...), kill %1, ... | xargs kill) can hit other agents on this Mac.
Record the PID when you start a process, then signal only that PID:
    cmd & pid=$!
    kill <pid>        # or: kill "$pid"
For Gradle, run ./gradlew --stop only for a daemon you started."""

WRAPPERS = {"sudo", "env", "command", "exec", "nohup", "time", "nice", "builtin"}
KEYWORDS = {"if", "then", "do", "else", "elif", "while", "until", "{", "!"}
OPCHARS = ";&|<>()"
REDIRECT = re.compile(r"^\d*(&?>>?|>&|>\||<<?<?|<&|<>)$")
HEREDOC = re.compile(r"(?<!<)<<(?!<)-?[ \t]*([\x27\"]?)(\w+)\1")
PID = re.compile(r"^-?\d+$")
PARAM = re.compile(r"^-?\$(\w+|[!$@*]|\{[^}`(]*\})$")
PIDFILE = re.compile(r"^\$\(cat[ \t]+[^\s$`;&|()<>\"\x27\\]+\)$")
SIGNAL = re.compile(r"^-\w+$")


def refuse():
    sys.stderr.write(MSG + "\n")
    sys.exit(2)


def strip_heredocs(s):
    out = []
    pos = 0
    while True:
        m = HEREDOC.search(s, pos)
        if not m:
            out.append(s[pos:])
            break
        eol = s.find("\n", m.end())
        if eol < 0:
            out.append(s[pos:])
            break
        out.append(s[pos:eol + 1])
        end = re.compile(r"^[ \t]*" + re.escape(m.group(2)) + r"[ \t]*$", re.M).search(s, eol + 1)
        if not end:
            break  # unterminated: ignore the rest
        pos = end.end()
    return "".join(out)


def tokenize(s):
    """Return ("w", word) and ("o", operator) tokens. Raises on an unterminated quote."""
    toks = []
    word = ""
    has_word = False
    i = 0
    n = len(s)

    def flush():
        nonlocal word, has_word
        if has_word:
            toks.append(("w", word))
        word = ""
        has_word = False

    while i < n:
        c = s[i]
        if c in " \t\r":
            flush()
            i += 1
        elif c == "\n":
            flush()
            toks.append(("o", ";"))
            i += 1
        elif c == "#" and not has_word:
            while i < n and s[i] != "\n":
                i += 1
        elif c == "\\":
            if i + 1 < n and s[i + 1] == "\n":
                i += 2
            else:
                word += s[i:i + 2]
                has_word = True
                i += 2
        elif c in "\x27\"":
            j = i + 1
            while True:
                if j >= n:
                    raise ValueError("unterminated quote")
                if c == "\"" and s[j] == "\\":
                    j += 2
                    continue
                if s[j] == c:
                    break
                j += 1
            word += s[i:j + 1]
            has_word = True
            i = j + 1
        elif c in OPCHARS:
            op = ""
            if has_word and word.isdigit() and c in "<>":
                op = word  # a file descriptor: 2> and the like
                word = ""
                has_word = False
            flush()
            while i < n and s[i] in OPCHARS:
                op += s[i]
                i += 1
            toks.append(("o", op))
        else:
            word += c
            has_word = True
            i += 1
    flush()
    return toks


def plain(w):
    return re.sub(r"[\"\x27\\]", "", w)


def is_target(t):
    if len(t) >= 2 and t[0] == "\"" and t[-1] == "\"":
        inner = t[1:-1]
        return bool(PID.match(inner) or PARAM.match(inner) or PIDFILE.match(inner))
    return bool(PID.match(t) or PARAM.match(t))


def kill_ok(args):
    if args and args[0] in ("-l", "-L"):
        return True
    targets = 0
    after_signal = False
    end_of_options = False
    i = 0
    while i < len(args):
        a = args[i]
        if not end_of_options and a == "--":
            end_of_options = True
        elif not end_of_options and a in ("-s", "-n"):
            after_signal = True
            i += 1
        elif not end_of_options and a.startswith("-") and len(a) > 1 and not (after_signal and is_target(a)):
            after_signal = after_signal or bool(SIGNAL.match(a))
        elif is_target(a):
            targets += 1
        else:
            return False
        i += 1
    return targets > 0


def check_segment(words):
    i = 0
    while i < len(words):
        w = words[i]
        if w in KEYWORDS or re.match(r"^\w+=", w):
            i += 1
        elif w in WRAPPERS:
            i += 1
            while i < len(words) and words[i].startswith("-"):
                i += 1
        else:
            break
    if i >= len(words):
        return
    cmd = plain(words[i]).rsplit("/", 1)[-1]
    args = words[i + 1:]
    if cmd in ("pkill", "killall"):
        refuse()
    if cmd == "kill" and not kill_ok(args):
        refuse()
    if cmd == "xargs" and any(plain(a).rsplit("/", 1)[-1] in ("kill", "pkill", "killall") for a in args):
        refuse()


try:
    tokens = tokenize(strip_heredocs(json.load(sys.stdin)["tool_input"]["command"]))
    words = []
    skip = False
    for kind, text in tokens:
        if kind == "w":
            if skip:
                skip = False
            else:
                words.append(text)
        elif REDIRECT.match(text):
            skip = True
        else:
            skip = False
            check_segment(words)
            words = []
    check_segment(words)
except Exception:
    pass
'
