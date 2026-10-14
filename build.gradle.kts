// Top-level build file. Plugins are declared here (apply false) and applied per module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

/**
 * :core-romm and :core-storage compile against JDK 17 but run on Android 11 and up (minSdk 30). A
 * java.* call Android only gained later compiles and passes their JVM tests, then crashes on the
 * device: it happened with ZipEntry.setTimeLocal (API 35) and Stream.toList (API 34). This checks
 * every java/javax class, method and field their classes use against the SDK's api-versions.xml.
 */
abstract class AndroidApiCheck : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val classes: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apiVersions: RegularFileProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:OutputFile abstract val report: RegularFileProperty

    private class ApiClass(val since: Double, val supers: MutableList<String> = mutableListOf(), val members: MutableMap<String, Double> = mutableMapOf())

    @TaskAction fun check() {
        val api = readApi(apiVersions.get().asFile)
        val files = classes.asFileTree.matching { include("**/*.class") }.files.sorted()
        val javap = File(System.getProperty("java.home"), "bin/javap").path
        val ref = Regex("""// (Method|InterfaceMethod|Field|class) ((?:java|javax)/[\w/$]+)(?:\.([\w$<>"]+):(\S+))?""")
        val refs = files.chunked(200).flatMap { batch ->
            val process = ProcessBuilder(listOf(javap, "-c", "-p") + batch.map { it.path }).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "javap failed:\n$out" }
            ref.findAll(out).map { it.groupValues }.toList()
        }.toSet()
        val min = minSdk.get().toDouble()
        val missing = refs.mapNotNull { (_, kind, owner, name, desc) ->
            val since = if (kind == "class") api[owner]?.since else lowest(api, owner, name.trim('"') + if (kind == "Field") "" else desc)
            if (since == null || since > min) "${kind.lowercase()} ${owner.replace('/', '.')}${if (name.isEmpty()) "" else ".${name.trim('"')}$desc"} (Android has it from ${since ?: "never"})" else null
        }.sorted()
        report.get().asFile.writeText(missing.joinToString("\n", postfix = "\n"))
        if (missing.isNotEmpty()) throw GradleException("Not on Android ${minSdk.get()}, though the JVM has them:\n  " + missing.joinToString("\n  "))
        logger.lifecycle("${refs.size} java/javax references in ${files.size} classes are all on Android ${minSdk.get()}.")
    }

    /** The lowest API level at which [owner] or one of its supertypes has [member]; null when none does. */
    private fun lowest(api: Map<String, ApiClass>, owner: String, member: String, seen: MutableSet<String> = mutableSetOf()): Double? {
        val c = api[owner] ?: return null
        if (!seen.add(owner)) return null
        val own = c.members[member]?.let { maxOf(it, c.since) }
        return (listOfNotNull(own) + c.supers.mapNotNull { lowest(api, it, member, seen) }).minOrNull()
    }

    private fun readApi(file: File): Map<String, ApiClass> {
        val classes = mutableMapOf<String, ApiClass>()
        var current: ApiClass? = null
        javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().parse(file, object : org.xml.sax.helpers.DefaultHandler() {
            override fun startElement(uri: String, local: String, name: String, attrs: org.xml.sax.Attributes) {
                val removed = attrs.getValue("removed") != null
                when (name) {
                    "class" -> current = ApiClass(attrs.getValue("since")?.toDouble() ?: 1.0).also { if (!removed) classes[attrs.getValue("name")] = it }
                    "extends", "implements" -> current?.supers?.add(attrs.getValue("name"))
                    "method", "field" -> current?.let { c -> if (!removed) c.members[attrs.getValue("name")] = attrs.getValue("since")?.toDouble() ?: c.since }
                }
            }
        })
        return classes
    }
}

val androidApiCheck by tasks.registering(AndroidApiCheck::class) {
    group = "verification"
    description = "Checks that :core-romm and :core-storage only use Java APIs Android 11 (API 30) has."
    for (module in listOf(":core-romm", ":core-storage")) {
        classes.from(project(module).tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory })
    }
    val sdkDir = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).orElse(
        providers.fileContents(layout.projectDirectory.file("local.properties")).asText.map { text ->
            text.lines().first { it.startsWith("sdk.dir=") }.substringAfter("=").trim()
        },
    )
    apiVersions.set(layout.file(sdkDir.map { File(it, "platforms/android-37.0/data/api-versions.xml") }))
    minSdk.set(30)
    report.set(layout.buildDirectory.file("reports/android-api-check.txt"))
}
