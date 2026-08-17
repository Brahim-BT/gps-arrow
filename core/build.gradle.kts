plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :core is deliberately a plain JVM module. It must never import anything from
// android.*, so that all of the navigation maths is unit-testable with `./gradlew :core:test`
// and stays reusable if the arrow is ever ported off Android.

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
