# Everything CI checks; run before handing work back (Gradle's check is the single definition).
check:
    ./gradlew check

# Vulnerabilities and licenses from the lockfiles and verification metadata; the CI audit job runs this.
audit:
    osv-scanner scan source --config=osv-scanner.toml --lockfile=gradle.lockfile --lockfile=buildscript-gradle.lockfile --lockfile=gradle/verification-metadata.xml --licenses=MIT,Apache-2.0,BSD-2-Clause,BSD-3-Clause,ISC,0BSD,BlueOak-1.0.0,CC0-1.0,Unlicense,EPL-1.0,EPL-2.0
