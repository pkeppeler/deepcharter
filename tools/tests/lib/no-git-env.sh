# Sourced by a test that runs git: drops every GIT_* variable that a hook, `rebase --exec` or `bisect run` exported, so git acts on
# the test's own temp repo and never on the real one (#372). tools/tests/test_git_env_scrub.py fails a test that runs git without it.
unset "${!GIT_@}"
