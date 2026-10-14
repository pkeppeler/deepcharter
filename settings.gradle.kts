pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
			content { includeGroupAndSubgroups("net.fabricmc") }
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// Resolves and downloads a missing JDK for the toolchain and the daemon JVM pin (gradle/gradle-daemon-jvm.properties).
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// RELAXED: no dependencyResolutionManagement with FAIL_ON_PROJECT_REPOS. Loom adds project repositories, which that mode rejects
// ("repository 'LoomLocalRemappedMods' was added by plugin class 'net.fabricmc.loom.LoomRepositoryPlugin'"), and PREFER_SETTINGS
// would ignore Loom's local Minecraft repositories. build.gradle.kts checks the project repositories instead.

rootProject.name = "deepcharter"
