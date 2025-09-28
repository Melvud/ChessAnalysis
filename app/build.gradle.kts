plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.chessanalysis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chessanalysis"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // Kotlin/AndroidX базовые
    implementation("androidx.core:core-ktx:1.16.0") // 1.17.0 требует KGP 2.0+ :contentReference[oaicite:0]{index=0}
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4") // Lifecycle 2.9.4 — актуальная стабильная :contentReference[oaicite:1]{index=1}
    implementation("androidx.activity:activity-compose:1.11.0") // стабильная от 24.09.2025 :contentReference[oaicite:2]{index=2}

    // JSON
    implementation("com.google.code.gson:gson:2.13.2") // актуальная на 10.09.2025 :contentReference[oaicite:3]{index=3}
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // актуальная стабильная :contentReference[oaicite:4]{index=4}

    // Compose (через BOM)
    implementation(platform("androidx.compose:compose-bom:2025.09.01")) // актуальная карта версий :contentReference[oaicite:5]{index=5}
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Навигация
    implementation("androidx.navigation:navigation-compose:2.9.0") // стабильная ветка 2.9.x :contentReference[oaicite:6]{index=6}

    // Сеть: OkHttp + Retrofit 3.x
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.1.0")) // OkHttp 5.1.0 stable :contentReference[oaicite:7]{index=7}
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")

    implementation("com.squareup.retrofit2:retrofit:3.0.0") // Retrofit 3.x stable :contentReference[oaicite:8]{index=8}
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.retrofit2:converter-scalars:3.0.0") // конвертеры 3.0.0 :contentReference[oaicite:9]{index=9}

    // Moshi (reflection-based)
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2") // последняя 1.15.2 :contentReference[oaicite:10]{index=10}

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // актуальные 1.10.2 :contentReference[oaicite:11]{index=11}

    // PGN/FEN
    implementation("com.github.bhlangonijr:chesslib:1.3.1") // обновлений совместимых без миграции не найдено надёжно

    // Изображения (Coil v2 — чтобы не ломать импорты)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0") // 2.7.0 — последняя ветки v2; v3 меняет пакеты/артефакты :contentReference[oaicite:12]{index=12}

    // Accompanist Snapper для центр-снаппинга
    implementation("dev.chrisbanes.snapper:snapper:0.3.0")

    // Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:33.16.0")) // последняя на 26.06.2025 :contentReference[oaicite:13]{index=13}
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Material (Android Views)
    implementation("com.google.android.material:material:1.12.0")

    // Room (оставляем 2.6.1 для совместимости с текущим Kotlin/агрегаторами)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Примечание: Room 2.7+ и 2.8+ требуют Kotlin 2.0+ и могут потребовать миграции KSP/KMP :contentReference[oaicite:14]{index=14}

    // Прочее
    implementation("com.squareup.okhttp3:logging-interceptor") // управляется BOM
    implementation("com.squareup.okhttp3:okhttp")             // управляется BOM
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
}
