// CI only (ADR 0101): Maven Central is resolved from Google's official mirror of it, because Central answers 429 to
// GitHub's runners for minutes to hours (#1365). tools/ci-gradle-resolve.sh installs this file into the runner's
// $GRADLE_USER_HOME/init.d on GitHub Actions only, so every Gradle call in a CI job applies it and no dev build does.
//
// Every repository whose URL is Central's (settings' mavenCentral() for plugins, Loom's for the project, any
// buildscript's) takes the mirror's URL in its place, keeping its name and its position, so the repository order and
// content filters are unchanged and Central itself is never asked. Integrity does not depend on the host:
// gradle/verification-metadata.xml pins every artifact's sha256 and the lockfiles pin every version.

val central = "https://repo.maven.apache.org/maven2/"
val mirror = uri("https://maven-central.storage-download.googleapis.com/maven2/")

fun RepositoryHandler.centralFromMirror() = configureEach {
	if (this is MavenArtifactRepository && url.toString().trimEnd('/') + "/" == central) {
		url = mirror
	}
}

beforeSettings {
	pluginManagement.repositories.centralFromMirror()
	buildscript.repositories.centralFromMirror()
	dependencyResolutionManagement.repositories.centralFromMirror()
}

allprojects {
	buildscript.repositories.centralFromMirror()
	repositories.centralFromMirror()
}
