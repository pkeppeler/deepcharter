# gate-sweep fixtures

- `vanilla-sound-gate-failure.gradle.log`: verbatim output of `./gradlew --console=plain --continue --max-workers=2 checkVanillaSoundGate`
  as `tools/gate-sweep.sh --local` ran it on a throwaway branch that banned `MoleState.WRECKED`, over PR #1634 merged with main
  (2026-11-28). Only the two `Picked up JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` lines the JVM prints first were dropped, whole.
