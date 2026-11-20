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
