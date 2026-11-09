plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    api("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    compileOnly("org.json:json:20240303")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
