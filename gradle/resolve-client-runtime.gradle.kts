// resolveClientGameTestRuntime: downloads and verifies everything runClientGameTest puts on its classpath, without
// starting the game. `classes` resolves only the compile classpaths, so a broken runtime-only artifact would otherwise
// fail minutes into the client run. CI's tools/ci-gradle-resolve.sh runs it with `classes` (#830).
// The gametest source set is the client run's mod (fabricApi.configureTests); the client source set is the game's own code.
val sourceSetContainer = project.the<SourceSetContainer>()
tasks.register("resolveClientGameTestRuntime") {
	description = "Resolves the runtime classpaths of the client GameTest run, for CI's cache pre-flight."
	group = "verification"
	val classpaths = listOf("gametest", "client").map { name ->
		configurations.getByName(sourceSetContainer.getByName(name).runtimeClasspathConfigurationName).incoming.files
	}
	doLast {
		// Reading each file forces its download and its dependency verification.
		classpaths.forEach { classpath -> classpath.files }
	}
}
