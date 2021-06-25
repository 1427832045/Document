plugins {
    `maven-publish`
}

//version = "0.1.4"

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("default") {
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }
}

dependencies {
    implementation(project(":srd-encrypt"))
}