plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    // Android supplies org.json at runtime; JVM tests use this implementation.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
