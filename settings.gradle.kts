/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import java.util.Properties

rootProject.name = "animeko"

pluginManagement {
    // 约定插件来自 build-logic; 必须在 settings 里 includeBuild, plugins {} 才解析得到 `ani.*`.
    includeBuild("build-logic")

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") // Compose Multiplatform pre-release versions
    }
}

dependencyResolutionManagement {
    // 仓库策略属于 settings; FAIL_ON_PROJECT_REPOS 防止子项目再自己加仓库.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    @Suppress("UnstableApiUsage")
    repositories {
        // mavenLocal 的位置不能动: 本地 mediamp / anitorrent 调试构建依赖它排在这里.
        mavenCentral()
        google()
        mavenLocal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://androidx.dev/storage/compose-compiler/repository/")
        maven("https://jogamp.org/deployment/maven")
    }
    versionCatalogs {
        create("anitorrentLibs") {
            from("org.openani.anitorrent:catalog:0.2.0")
        }

    }
}

plugins {
    id("com.gradle.develocity") version "4.3.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

develocity {
    buildScan {
        // Keep scans opt-in via --scan and only allow publication from CI.
        publishing.onlyIf { !System.getenv("CI").isNullOrEmpty() }
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        uploadInBackground = System.getenv("CI").isNullOrEmpty()
    }
}

fun includeProject(projectPath: String, dir: String? = null) {
    include(projectPath)
    if (dir != null) project(projectPath).projectDir = file(dir)
}

// Utilities shared by client and server (targeting JVM)
includeProject(":utils:platform") // 适配各个平台的基础 API
includeProject(":utils:intellij-annotations")
includeProject(":utils:logging") // shared by client and server (targets JVM)
includeProject(":utils:serialization", "utils/serialization")
includeProject(":utils:coroutines", "utils/coroutines")
includeProject(":utils:ktor-client", "utils/ktor-client")
includeProject(":utils:io", "utils/io")
includeProject(":utils:testing", "utils/testing")
includeProject(":utils:xml")
includeProject(":utils:jsonpath")
includeProject(":utils:bbcode", "utils/bbcode")
includeProject(":utils:bbcode:test-codegen")
includeProject(":utils:ip-parser", "utils/ip-parser")
includeProject(":utils:ui-testing")
includeProject(":utils:androidx-lifecycle-runtime-testing")
includeProject(":utils:ui-preview")
includeProject(":utils:analytics")
includeProject(":utils:http-downloader")
includeProject(":utils:build-config")
includeProject(":utils:video-enhancement-shader-provider")
includeProject(":utils:selector-workflow") // 数据源选择流程示意动画的数据层


includeProject(":torrent:torrent-api", "torrent/api") // Torrent 系统 API
includeProject(":torrent:anitorrent")
//includeProject(":torrent:anitorrent:anitorrent-native")
includeProject(":torrent:pikpak") // PikPak 云离线下载后端

includeProject(":app:shared")
includeProject(":app:shared:app-platform")
includeProject(":app:shared:app-data")
includeProject(":app:shared:app-data-aidl")
includeProject(":app:shared:app-lang") // We have a separate module so that the project compiles faster
includeProject(":app:shared:ui-foundation")
includeProject(":app:shared:ui-settings")
includeProject(":app:shared:ui-adaptive")
includeProject(":app:shared:ui-subject")
includeProject(":app:shared:ui-cache")
includeProject(":app:shared:ui-exploration")
includeProject(":app:shared:ui-comment")
includeProject(":app:shared:ui-onboarding")
includeProject(":app:shared:ui-mediaselect")
includeProject(":app:shared:ui-episode")
includeProject(":app:shared:ui-exprovider")
includeProject(":app:shared:ui-watchtogether")
includeProject(":app:shared:video-player:video-player-api", "app/shared/video-player/api")
includeProject(":app:shared:video-player:torrent-source")
includeProject(":app:shared:video-player")
includeProject(":app:shared:application")

includeProject(":app:shared:placeholder", "app/shared/thirdparty/placeholder")
includeProject(":app:shared:paging-compose", "app/shared/thirdparty/paging-compose")
includeProject(":app:shared:reorderable", "app/shared/thirdparty/reorderable")

includeProject(":app:desktop", "app/desktop") // desktop JVM client for macOS, Windows, and Linux
includeProject(":app:android", "app/android") // Android client
includeProject(":app:ios", "app/ios") // iOS Launcher

includeProject(":client")

// server
//includeProject(":server:core", "server/core") // server core
//includeProject(":server:database", "server/database") // server database interfaces
//includeProject(":server:database-xodus", "server/database-xodus") // database implementation with Xodus

// data sources
includeProject(":datasource:datasource-api", "datasource/api") // data source interfaces: Media, MediaSource 
includeProject(":datasource:datasource-api:test-codegen", "datasource/api/test-codegen") // 生成单元测试
includeProject(
    ":datasource:datasource-core",
    "datasource/core",
) // data source managers: MediaFetcher, MediaCacheStorage
includeProject(":datasource:bangumi", "datasource/bangumi") // https://bangumi.tv
//   BT 数据源
includeProject(":datasource:dmhy", "datasource/bt/dmhy") // https://dmhy.org
includeProject(":datasource:mikan", "datasource/bt/mikan") // https://mikanani.me/
//   Web 数据源
includeProject(":datasource:web-base", "datasource/web/web-base") // web 基础
includeProject(":datasource:jellyfin", "datasource/jellyfin")
includeProject(":datasource:ikaros", "datasource/ikaros") // https://ikaros.run/

// danmaku
includeProject(":danmaku:danmaku-ui-config", "danmaku/ui-config")
includeProject(":danmaku:danmaku-api", "danmaku/api")
includeProject(":danmaku:danmaku-ui", "danmaku/ui")
includeProject(":danmaku:dandanplay", "danmaku/dandanplay")

includeProject(
    ":datasource:dmhy:dataset-tools",
    "datasource/bt/dmhy/dataset-tools",
) // tools for generating dataset for ML title parsing

// ci
includeProject(":ci-helper", "ci-helper") // 
includeProject(
    ":ci-helper:sqlite-woa64",
    "ci-helper/sqlite-woa64",
) // Windows ARM64 SQLite natives, see its build.gradle.kts
includeProject(":tools:datasource-test-mcp", "tools/datasource-test-mcp")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")


// settings 先于 build-logic 构建, 拿不到 LocalPropertiesValueSource, 这里单独实现一份.
val localProperties: Provider<Properties> =
    providers.fileContents(layout.settingsDirectory.file("local.properties")).asText
        .map { text -> Properties().apply { text.reader().use { load(it) } } }

fun findLocalProperty(key: String): String? = localProperties.orNull?.getProperty(key)

// settings 里拿不到 build-logic 的 helpers, 这里按 local.properties -> 环境变量 的顺序读.
fun findLocalPropertyOrEnv(key: String): String? =
    findLocalProperty(key) ?: System.getenv(key)

// mediamp 来自仓库内的 submodule(thirdparty/mediamp,即 AAAA1111-Lab/mediamp 的 fork),
// 以复合构建的方式从源码消费, 因此**不需要任何凭据**.
//
// 为什么不用包仓库: GitHub Packages 即使对 public 包也拒绝匿名下载(实测 pom/module/aar 全 401),
// 于是每个消费端构建都要带一个迟早过期的 token. 从源码组合把凭据从整条链上拿掉了.
//
// 消费模式开关: 复合构建会配置 included build 的所有子项目, 而 fork 的 mediamp-ffmpeg 在配置期
// 就要解析 MSYS2, 在 Windows 上会直接让整个构建失败(实测). 因此从 fork 侧用
// -Dmediamp.consumer=true 只纳入可链接的库模块. 用系统属性而不是 Gradle 属性, 因为前者在
// includeBuild 之前设置一定能被 included build 读到.
//
// 本地调试仍然可以用 local.properties 的 ani.build.mediamp.path 指向任意 fork 检出,
// 此时会覆盖 submodule 的路径.
val mediampOverridePath = findLocalProperty("ani.build.mediamp.path")
val mediampCompositePath = mediampOverridePath ?: layout.settingsDirectory.dir("thirdparty/mediamp").asFile.path
if (mediampOverridePath != null) {
    println("i:: Including mediamp from the ani.build.mediamp.path override: $mediampOverridePath")
} else if (file(mediampCompositePath).isDirectory) {
    println("i:: Including mediamp from the bundled submodule: $mediampCompositePath")
}

if (mediampOverridePath != null || file(mediampCompositePath).isDirectory) {
    val previousConsumerFlag = System.getProperty("mediamp.consumer")
    System.setProperty("mediamp.consumer", "true")
    includeBuild(mediampCompositePath) {
        dependencySubstitution {
            substitute(module("org.openani.mediamp:mediamp-api"))
                .using(project(":mediamp-api"))
            substitute(module("org.openani.mediamp:mediamp-exoplayer"))
                .using(project(":mediamp-exoplayer"))
            substitute(module("org.openani.mediamp:mediamp-test"))
                .using(project(":mediamp-test"))
            substitute(module("org.openani.mediamp:mediamp-source-ktxio"))
                .using(project(":mediamp-source-ktxio"))
            substitute(module("org.openani.mediamp:mediamp-internal-utils"))
                .using(project(":mediamp-internal-utils"))
        }
    }
    if (previousConsumerFlag == null) {
        System.clearProperty("mediamp.consumer")
    } else {
        System.setProperty("mediamp.consumer", previousConsumerFlag)
    }
}

// GitHub Packages 只作为兜底: 只有在显式提供了地址与凭据时才加, 否则匿名请求会拿到 401,
// 反而让所有依赖解析失败. 正常路径(上面的复合构建)不需要它.
findLocalPropertyOrEnv("githubPackagesUrl")?.let { url ->
    val username = findLocalPropertyOrEnv("githubPackagesUsername")
    val password = findLocalPropertyOrEnv("githubPackagesPassword")
    if (username != null && password != null) {
        println("i:: Adding GitHub Packages repository: $url")
        dependencyResolutionManagement.repositories.maven {
            name = "GitHubPackages"
            setUrl(url)
            credentials {
                this.username = username
                this.password = password
            }
            content {
                includeGroup("org.openani.mediamp")
            }
        }
    } else {
        println("w:: githubPackagesUrl is set but no credentials; skipping GitHub Packages repository")
    }
}

findLocalProperty("ani.build.anitorrent.path")?.let { anitorrentPath ->
    println("i:: Including anitorrent as a Composite Build from: $anitorrentPath")
    includeBuild(anitorrentPath) {
        dependencySubstitution {
            substitute(module("org.openani.anitorrent:anitorrent-native"))
                .using(project(":anitorrent-native"))
            substitute(module("org.openani.anitorrent:anitorrent-native-desktop-jni"))
                .using(project(":anitorrent-native-desktop-jni"))
        }
    }
}
