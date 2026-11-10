plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.llq.hello"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.llq.hello"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // App 内工程把源码放在 src/main/kotlin，而 AGP 默认只认 src/main/java：两边都声明
    sourceSets["main"].java.srcDirs("src/main/java", "src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 显式声明 debug/release：编辑器构建引擎按声明算可用变体，不声明会报「变体不存在」
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // 引擎会把依赖池（build-deps/libs）里的 aar/jar 全部进 classpath；
    // 这里声明坐标：一是给真 Gradle 用，二是让工程自解释需要哪些库。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
