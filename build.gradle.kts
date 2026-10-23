// same toolchain as the DT Keyboard project (AGP 9 with built-in Kotlin)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath(kotlin("gradle-plugin", version = "2.4.0"))
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
