plugins {
    application
}

dependencies {
    implementation(project(":srd-common"))
}

application {
    // Define the main class for the application.
    mainClassName = "com.seer.srd.junyi.JunYiAppKt"
}