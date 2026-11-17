plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkVersion = "0.1.0"

android {
    namespace = "kr.exam.rfscan"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "kr.exam"
                artifactId = "rfscan"
                version = sdkVersion
                pom {
                    name.set("rfscan")
                    description.set("Passive Bluetooth LE + Wi-Fi presence detection for exam rooms (receive-only)")
                }
            }
        }
        repositories {
            // GitHub Packages 배포: ./gradlew :rfscan:publish  (GPR_USER, GPR_TOKEN, GPR_REPO 환경변수 필요)
            val repo = System.getenv("GPR_REPO")
            if (repo != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$repo")
                    credentials {
                        username = System.getenv("GPR_USER")
                        password = System.getenv("GPR_TOKEN")
                    }
                }
            }
        }
    }
}
