plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

subprojects {
    layout.buildDirectory.set(file("${System.getenv("LOCALAPPDATA")}/jac-diagnostic-lab-build/${project.name}"))
}
