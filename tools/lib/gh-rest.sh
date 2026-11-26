# Sourced by the merge tools: PR reads through the REST API only. The cloud sessions' egress proxy blocks GitHub
# GraphQL, which every `gh pr` and `gh issue` subcommand uses (#788); tools/tests/no-gh-graphql.test.sh keeps it out.
# The caller sets $repo. Compatible with bash 3.2.

# The one account whose comments count (the repo is public: only this login is trusted).
TRUSTED_LOGIN=pkeppeler

# pr_get <n> <jq>: one filter over GET pulls/<n>. Fails when gh fails.
pr_get() { gh api "repos/$repo/pulls/$1" --jq "$2"; }

# pr_state_of <n>: OPEN, CLOSED or MERGED. Fails when unreadable.
pr_state_of() {
  local line merged state
  line=$(pr_get "$1" '"\(.merged) \(.state)"') || return 1
  read -r merged state <<<"$line"
  if [[ $merged == true ]]; then
    echo MERGED
  elif [[ $state == open ]]; then
    echo OPEN
  elif [[ $state == closed ]]; then
    echo CLOSED
  else
    return 1
  fi
}

# pr_mergeable_of <n>: MERGEABLE, CONFLICTING, or UNKNOWN while GitHub still computes it (REST: null; tostring because gh
# prints a null as an empty line).
pr_mergeable_of() {
  local value
  value=$(pr_get "$1" '.mergeable | tostring') || return 1
  case $value in
    true) echo MERGEABLE ;;
    false) echo CONFLICTING ;;
    null) echo UNKNOWN ;;
    *) return 1 ;;
  esac
}

# gh_list <var> <path> <jq>: every page of a GET listing, through --jq, into <var>; or fails with gh's error in
# $gh_list_err and leaves <var> empty. `gh api --paginate` exits nonzero when any page fails but has already printed
# the pages before it, so a listing that lost a page is never used (#1484). gh's stderr stays out of <var>. A
# transient error (HTTP 500, 502, 503, 504, a timeout or a reset connection) retries the whole listing:
# GH_LIST_ATTEMPTS tries (default 3), sleeping GH_LIST_SLEEP seconds (default 5) times the attempt number between them;
# both must be whole numbers. A rate limit (429, or a secondary-limit 403) is not retried: it refuses at once, closed.
gh_list() {
  local attempts=${GH_LIST_ATTEMPTS:-3} pause=${GH_LIST_SLEEP:-5} attempt=1 errfile listed
  local transient='HTTP 50[0234]|[Tt]imeout|timed out|deadline exceeded|connection reset|unexpected EOF'
  printf -v "$1" '%s' ''
  gh_list_err=
  if [[ ! $attempts =~ ^[1-9][0-9]*$ || ! $pause =~ ^[0-9]+$ ]]; then
    gh_list_err="GH_LIST_ATTEMPTS and GH_LIST_SLEEP must be whole numbers (attempts at least 1)"
    return 1
  fi
  errfile=$(mktemp) || { gh_list_err="could not create a temporary file"; return 1; }
  while true; do
    if listed=$(gh api "$2" --paginate --jq "$3" 2>"$errfile"); then
      rm -f "$errfile"
      printf -v "$1" '%s' "$listed"
      return 0
    fi
    gh_list_err=$(tr '\n' ' ' <"$errfile")
    gh_list_err=${gh_list_err% }
    [[ -n $gh_list_err ]] || gh_list_err="gh exited nonzero with no error text"
    if [[ $attempt -ge $attempts || ! $gh_list_err =~ $transient ]]; then
      rm -f "$errfile"
      return 1
    fi
    echo "gh api $2 failed (attempt $attempt of $attempts, retrying): $gh_list_err" >&2
    sleep $((pause * attempt))
    attempt=$((attempt + 1))
  done
}
