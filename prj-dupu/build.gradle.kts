plugins {
    application
}

dependencies {
    implementation(project(":srd-common"))
}

application {
    mainClassName = "com.seer.srd.dupu.DupuAppKt"
}