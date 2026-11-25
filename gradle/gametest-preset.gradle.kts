import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// The test mod's flat_all_dimensions preset is generated (#1158): never edit it, and it is never committed.
// src/gametest/templates/world_preset/flat_all_dimensions.json is its source. An entry there of the form
// {"$from": "<namespace>:<name>", <key>: <value>...} is the shipped dimension src/main/resources/data/<namespace>/dimension/<name>.json
// with those top-level keys replaced, so a change to the campaign generator reaches every window of it without a hand edit.
// A campaign_full or campaign_<band> entry that is not derived this way fails the build: a pasted copy is what drifted.
object PresetDerive {
	private val derivedNames = Regex("deepcharter:campaign_(full|surface|layer_\\d+)(_floor)?")
	private const val FROM = "\$from"

	fun derive(template: Map<*, *>, templatePath: File, dimensionsDir: (String, String) -> File): String {
		val dimensions = LinkedHashMap<String, Any?>()
		val templateDimensions = template["dimensions"] as? Map<*, *>
			?: throw GradleException("Preset template $templatePath needs a top-level \"dimensions\" object of dimension id to entry")
		templateDimensions.forEach { (rawId, rawEntry) ->
			val id = rawId as String
			val entry = rawEntry as Map<*, *>
			if (!entry.containsKey(FROM)) {
				if (derivedNames.matches(id)) {
					throw GradleException("Preset entry $id is a copy: write {\"$FROM\": \"minecraft:overworld\", ...} so it follows the shipped dimension")
				}
				dimensions[id] = entry
				return@forEach
			}
			if (entry.containsKey("generator")) {
				throw GradleException("Preset entry $id has $FROM and its own generator: a derived entry overrides only its other keys, never the generator")
			}
			val source = (entry[FROM] as String).split(":", limit = 2)
			if (source.size != 2) throw GradleException("Preset entry $id: $FROM '${entry[FROM]}' is not <namespace>:<name>")
			val file = dimensionsDir(source[0], source[1])
			if (!file.isFile) throw GradleException("Preset entry $id: no shipped dimension at $file")
			val derived = LinkedHashMap<String, Any?>(JsonSlurper().parse(file, "UTF-8") as Map<String, Any?>)
			entry.forEach { (k, v) -> if (k != FROM) derived[k as String] = v }
			dimensions[id] = derived
		}
		return JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("dimensions" to dimensions))) + "\n"
	}
}

abstract class GeneratePreset : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val template: RegularFileProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val shippedData: DirectoryProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun run() {
		val out = outputDir.get().asFile
		out.deleteRecursively()
		val templateFile = template.get().asFile
		val text = PresetDerive.derive(JsonSlurper().parse(templateFile, "UTF-8") as Map<*, *>, templateFile) { ns, name ->
			File(shippedData.get().asFile, "$ns/dimension/$name.json")
		}
		val target = File(out, "data/minecraft/worldgen/world_preset/flat_all_dimensions.json")
		target.parentFile.mkdirs()
		target.writeText(text, Charsets.UTF_8)
	}
}

val generateGametestPreset = tasks.register<GeneratePreset>("generateGametestPreset") {
	template = layout.projectDirectory.file("src/gametest/templates/world_preset/flat_all_dimensions.json")
	shippedData = layout.projectDirectory.dir("src/main/resources/data")
	outputDir = layout.buildDirectory.dir("generated/gametest-preset")
}

tasks.named<ProcessResources>("processGametestResources") { from(generateGametestPreset) }
