plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.graphhopper:graphhopper-core:10.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("no.roadnotifications.importer.OsmGraphImporter")
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Xmx8g")
    val csvDir = rootProject.file("scripts/csv")
    val osmFile = csvDir.listFiles()
        ?.filter { file ->
            file.isFile && (
                file.name.endsWith(".osm.pbf", ignoreCase = true) ||
                    file.name.endsWith(".pbf", ignoreCase = true)
                )
        }
        ?.maxByOrNull { file -> file.lastModified() }
        ?: throw GradleException(
            "Fant ingen OSM PBF i ${csvDir.absolutePath}. Legg norway-latest.osm.pbf der.",
        )
    val outputFile = rootProject.file("app/src/main/assets/roadgraph.db")
    val cacheDir = rootProject.file("scripts/graphhopper-cache")
    args(
        osmFile.absolutePath,
        outputFile.absolutePath,
        cacheDir.absolutePath,
    )
    doFirst {
        logger.lifecycle("Importerer ${osmFile.name} → ${outputFile.absolutePath}")
    }
}
