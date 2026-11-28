import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        // 单包统一 minSdk 21（Android 5.0+）：≥0.30.10 的客户端装前校验只要求「APK 实际 minSdk == 清单声明」，
        // 声明 21 后清单对所有设备放行，Android 5-7 与 Android 8+ 共用一个包，不再出 android5 变体。
        // ≤0.28.0 的旧客户端要求声明 26 且只认 -android8.apk，已按放弃处理（2026-11-16 决定）。
        minSdk = 21
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 12
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.37.1"
        // 桌面名称：app_name 迁到资源文件（res/values/strings.xml + values-zh-rTW 繁体变体），
        // vc2test 验证包名在 src/vc2/res/values/strings.xml 覆盖为「红果TV·融合」防点错图标。
        // 原先 gradle resValue 无法按系统语言出繁体（用户反馈：繁中地区希望显示「紅果TV」）。
    }
    buildFeatures { buildConfig = true }
    // 编译开关（2026-11-21 资产隔离）：-Pvc2test 才把 src/vc2（解码器 JNI 门面 + decoder 包 +
    // 融合实现 + jniLibs 的 libbvc2dec.so）挂进构建。这些文件永不入库，CI/正式版构建没有它们
    // 也能编译——运行时 Vc2.api 反射不到实现就回落 Vc2ApiNoop，bvc2 档位走「不兼容 + 自动跳过」。
    sourceSets {
        if (project.hasProperty("vc2test")) {
            getByName("main") {
                java.srcDirs("src/vc2/kotlin")
                jniLibs.srcDir("src/vc2/jniLibs")
            }
        }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        // 带 -Pvc2test 时生成独立包名的验证包，可与正式版共存、不覆盖用户数据。
        // release 也可带该后缀，用于在真机上验证 R8 混淆后 JNI 注册仍正常。
        getByName("debug") { if (project.hasProperty("vc2test")) applicationIdSuffix = ".vc2test" }
        getByName("release") {
            if (project.hasProperty("vc2test")) applicationIdSuffix = ".vc2test"
            signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    // bcprov 只用到 SM3Digest，PQC/X509 的附属资源文件（约 1.2MB）用不到，打包时排除
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "org/bouncycastle/pqc/**", "org/bouncycastle/x509/**") }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    // 本地单测：CMS 解析跑在 JVM，需要真实 org.json 实现（Android 平台 stub 未 mock）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // CMS v1 XML 解析用 XmlPullParserFactory，JVM 测试需要实现库（Android 自带 kxml 实现）
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
