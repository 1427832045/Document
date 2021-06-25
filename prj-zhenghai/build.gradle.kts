plugins {
    application
}

dependencies {
    implementation(project(":srd-common"))
//    implementation("com.google.code.gson:gson:2.8.2")
}

application {
    // Define the main class for the application.
    mainClassName = "com.seer.srd.zhenghai.ZhengHaiAppKt"
}