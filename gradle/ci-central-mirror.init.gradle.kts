// CI only (ADR 0101): Maven Central is resolved from Google's mirror of it, because Central answers 429 to
// GitHub's runners for minutes to hours (#1365). tools/ci-gradle-resolve.sh installs this file into the runner's
// $GRADLE_USER_HOME/init.d on GitHub Actions only, so every Gradle call in a CI job applies it and no dev build does.
//
// Every repository whose URL is Central's, under either of its host names (settings' mavenCentral() for plugins,
// Loom's for the project, any buildscript's), takes the mirror's URL in its place, keeping its name and its position,
// so the repository order and content filters are unchanged and Central itself is never asked. Integrity does not
// depend on the host: gradle/verification-metadata.xml pins every artifact's sha256 and the lockfiles pin every
// version.
//
// The rewrite must never stop matching in silence (a Gradle API change, a plugin that spells Central another way):
// that would send CI back to Central and its 429s. So before the first plugin resolves, and again once settings are
// evaluated, the build fails if a plugin or settings repository still names Central, or if no plugin repository
// names the mirror; build.gradle.kts's allow-list refuses a project repository on Central on GitHub Actions.

val centralHosts = setOf("repo.maven.apache.org", "repo1.maven.org")
val mirror = uri("https://maven-central.storage-download.googleapis.com/maven2/")

fun ArtifactRepository.urlText(): String = (this as? MavenArtifactRepository)?.url?.toString().orEmpty()

fun ArtifactRepository.isCentral(): Boolean = centralHosts.any { urlText().startsWith("https://$it/maven2") }

fun RepositoryHandler.centralFromMirror() = configureEach {
	if (isCentral()) {
		(this as MavenArtifactRepository).url = mirror
	}
}

// Fails the build if a settings repository still names Central, or no plugin repository names the mirror.
fun Settings.checkNoCentral() {
	val settingsRepositories =
		pluginManagement.repositories + buildscript.repositories + dependencyResolutionManagement.repositories
	val central = settingsRepositories.filter { repo -> centralHosts.any { it in repo.urlText() } }
	check(central.isEmpty()) {
		"ci-central-mirror.init.gradle.kts left Maven Central in the settings repositories " +
			"${central.map { "${it.name} ${it.urlText()}" }}: CI must resolve Central from the mirror (ADR 0101)."
	}
	check(pluginManagement.repositories.any { it.urlText() == mirror.toString() }) {
		"ci-central-mirror.init.gradle.kts found no plugin repository on the mirror $mirror " +
			"${pluginManagement.repositories.map { "${it.name} ${it.urlText()}" }}: the rewrite no longer matches (ADR 0101)."
	}
}

beforeSettings {
	pluginManagement.repositories.centralFromMirror()
	buildscript.repositories.centralFromMirror()
	dependencyResolutionManagement.repositories.centralFromMirror()
	// Before the first plugin request resolves, so a Central left in place never gets asked.
	val settings = this
	pluginManagement.resolutionStrategy.eachPlugin { settings.checkNoCentral() }
}

// Again once settings are evaluated, for a build whose settings request no plugin.
settingsEvaluated { checkNoCentral() }

allprojects {
	buildscript.repositories.centralFromMirror()
	repositories.centralFromMirror()
}
