---
status: accepted
---

# CI resolves Maven Central from Google's mirror of it

[#1365](https://github.com/pkeppeler/deepcharter/issues/1365): Maven Central throttles GitHub's runners. The download retries [#1360](https://github.com/pkeppeler/deepcharter/pull/1360) added to `tools/ci-gradle-resolve.sh` cannot help with a throttle, and their diagnostics proved that it was one. On #1340's build (job 139531098863, 21:44 to 21:46 UTC on 2026-11-25), every attempt over 90 seconds, the final `--refresh-dependencies` one included, got **HTTP 429 from `repo.maven.apache.org`** for the foojay-resolver plugin marker. The script's own curl probe got 429 from Central and 200 from the Plugin Portal. That marker is not on Central at all: normally Central answers 404 and Gradle moves on to the portal. A 429 is a repository error, so Gradle fails the build instead of moving on, and a fallback repository after Central would never be reached. Cloud containers saw Central answer 429 for hours while Google's mirror served the same files.

## Decision

- **Hosts.** In CI, every repository whose URL is Central's (`https://repo.maven.apache.org/maven2/`) gets the URL of **Google's official Maven Central mirror** (`https://maven-central.storage-download.googleapis.com/maven2/`) instead. Each repository keeps its name, its place in the order and its content filters. That covers settings' `pluginManagement` `mavenCentral()`, Loom's project `mavenCentral()` and any buildscript or `dependencyResolutionManagement` one. In CI, Central is replaced, not kept as a fallback. The Gradle Plugin Portal (`plugins.gradle.org`), Fabric's maven, Mojang's libraries and GeckoLib's Cloudsmith repository are untouched.
- **How.** `gradle/ci-central-mirror.init.gradle.kts` does the rewrite with a `configureEach` on each repository handler, so it runs as the repository is added. `tools/ci-gradle-resolve.sh` copies that file into `$GRADLE_USER_HOME/init.d/` when `GITHUB_ACTIONS=true`, before its first Gradle run. Every workflow and composite action that sets up Gradle makes its first Gradle call through that script, and `tools/tests/ci-gradle-resolve-gate.test.sh` enforces this. So every later Gradle call in the job gets the init script too: the client shards' `runClientGameTest`, the build job's dry run, record-evidence and cache-seed. setup-gradle caches only the home's `caches` and `notifications`, so the file never travels in a cache entry. The script copies it fresh on every run.
- **CI only.** A dev machine never runs the script with `GITHUB_ACTIONS=true`, so its Gradle home never gets the file. Local builds resolve exactly as before, from Central first. `settings.gradle.kts` is unchanged. `build.gradle.kts`'s project repository allow-list accepts the mirror's URL only when `GITHUB_ACTIONS=true`.
- **Plugin markers keep their order.** In CI the order stays Fabric, then the mirror in Central's place, then the portal. The portal does not go ahead of the mirror: for any artifact that lives on Central, the portal answers with a 303 redirect to `repo.maven.apache.org` (checked on `junit:junit:4.13.2`), which would bring the throttled host back. A marker that is only on the portal, like foojay's, gets a 404 from the mirror and falls through to the portal as before.
- **Integrity comes from the checksums, not the host.** `gradle/verification-metadata.xml` (`verify-metadata` on, a sha256 for every pom, module and jar) and the strict lockfiles (`gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile`) are unchanged and fully enforced. Their entries are per artifact, not per repository, so a file from the mirror that differs from Central's fails verification just as a tampered file from Central would. Nothing in this change relaxes them.
- **The audit is unaffected.** osv-scanner (`audit.yml`, `just audit`) reads the lockfiles and the verification metadata, not repository URLs, and runs no Gradle.

## Reverting

Delete the `GITHUB_ACTIONS` block in `tools/ci-gradle-resolve.sh` that copies the init script, delete `gradle/ci-central-mirror.init.gradle.kts`, and drop the mirror line from `allowedRepositoryUrls` in `build.gradle.kts`. Nothing else depends on them. A runner's Gradle home is fresh for every job, so no installed copy outlives the revert.

## Considered options

- **Keep Central as a fallback after the mirror.** Rejected: a 404 from the mirror for a file that should exist would only happen during the mirror's short sync lag after a release, which affects a dependency bump PR at most, and the retries cover that. A trailing Central repository would also be asked for every file that legitimately lives elsewhere, and that brings back the host that fails.
- **Reorder `settings.gradle.kts` so the portal comes before Central.** Rejected for the reason above (the portal redirects Central's artifacts to Central), and because it would change local builds too.
- **Pass `--init-script` on each Gradle call.** Rejected: every `./gradlew` line in every workflow would need it, and a missing one would fall back to Central in silence. Installing into `init.d` from the one script that every job already calls first covers all of them.
- **A CI environment variable read by `settings.gradle.kts`.** Rejected: it puts CI-only routing into the files every local build evaluates.
- **More retries, or longer waits.** Rejected: the throttle outlasted 90 seconds of retries and lasts hours in other containers.
