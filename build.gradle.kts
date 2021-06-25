plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.41"
}

allprojects {
    group = "com.seer.srd"

    repositories {
        mavenLocal()

        maven { setUrl("https://mirrors.huaweicloud.com/repository/maven/") }

        //mavenCentral()
        //jcenter()
    }
}

configure(subprojects) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")

        implementation("org.apache.commons:commons-lang3:3.9")
        implementation("commons-io:commons-io:2.6")
        implementation("org.apache.commons:commons-csv:1.8")
        implementation("commons-codec:commons-codec:1.13")

        implementation("ch.qos.logback:logback-classic:1.2.3")

        implementation("org.mongodb:mongo-java-driver:3.12.0")
        implementation("org.litote.kmongo:kmongo:3.12.2")

        implementation("com.fasterxml.jackson.core:jackson-core:2.10.2")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.10.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.10.1")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.10.3")

        implementation("io.javalin:javalin:3.7.0")

        implementation("org.yaml:snakeyaml:1.25")

        implementation("ar.com.hjg:pngj:2.1.0")

        implementation("com.squareup.retrofit2:retrofit:2.5.0")
        implementation("com.squareup.retrofit2:converter-jackson:2.5.0")
        implementation("com.squareup.retrofit2:converter-jaxb:2.5.0")
        implementation("com.squareup.retrofit2:converter-scalars:2.5.0")
        implementation("com.squareup.okhttp3:logging-interceptor:3.12.1")

        implementation("org.mvel:mvel2:2.4.7.Final")

        implementation("com.digitalpetri.modbus:modbus-master-tcp:1.1.0")
        implementation("com.digitalpetri.modbus:modbus-slave-tcp:1.1.0")

        // Route
        implementation("com.google.inject:guice:4.2.1")
        implementation("com.google.inject.extensions:guice-assistedinject:4.2.0")

        implementation("redis.clients:jedis:3.2.0")

        implementation("org.apache.httpcomponents:fluent-hc:4.5.11")

        implementation("org.cfg4j:cfg4j-core:4.4.1")

        implementation("org.jdom:jdom2:2.0.6")

        //implementation("javax.xml.bind:jaxb-api:2.4.0-b180725.0427")
        //implementation("com.sun.xml.bind:jaxb-core:2.3.0")
        //implementation("com.sun.xml.bind:jaxb-impl:2.4.0-b180830.0438")
        //implementation("org.glassfish.jaxb:jaxb-runtime:2.4.0-b180725.0644")

        implementation("com.google.protobuf:protobuf-java:3.11.4")
        implementation("com.google.code.gson:gson:2.8.2")

        implementation("org.jgrapht:jgrapht-core:1.4.0")

        implementation("com.github.oshi:oshi-core:4.4.2")

        //implementation("org.hibernate:hibernate-core:5.4.2.Final")
        //implementation("javax.validation:validation-api:2.0.1.Final")
        //implementation("org.hibernate:hibernate-validator:5.4.2.Final")

        //implementation("com.sparkjava:spark-core:2.9.1")

        // testImplementation("org.jetbrains.kotlin:kotlin-test")
        // testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

        implementation("net.java.dev.jna:jna:5.5.0")
        implementation(files("../libs/SentinelLicensing.jar", "../libs/SentinelLicgenParser.jar"))

        implementation("commons-net:commons-net:3.6")
        implementation("org.quartz-scheduler:quartz:2.3.2")

        implementation("org.apache.poi:poi:4.1.2")
        implementation("org.apache.poi:poi-ooxml:4.1.2")
        implementation("com.sun.mail:javax.mail:1.6.2")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "1.8"
        }
    }

    java.sourceCompatibility = JavaVersion.VERSION_1_8
}