/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    id("ani.kmp-library")
    alias(libs.plugins.kotlin.plugin.serialization)

    // alias(libs.plugins.kotlinx.atomicfu)
}

// 声明桶与可解析配置分离, 每个 configuration 只承担一种角色.
val mediampFfmpegAppleRuntime = configurations.dependencyScope("mediampFfmpegAppleRuntime")
val mediampFfmpegAppleRuntimePath = configurations.resolvable("mediampFfmpegAppleRuntimePath") {
    extendsFrom(mediampFfmpegAppleRuntime.get())
    isTransitive = false
}

// 这里需要的是上游 mediamp-ffmpeg 的 ios runtime 版本, 与 libs.mediamp.ffmpeg.runtime.ios.xcframework
// 保持一致. 生成的 libs.versions.* 访问器对这个别名不可用(与 catalog 内部的 get(name) 撞名),
// 而 libs 暴露的是生成的 LibrariesForLibs, 拿不到 VersionCatalog.findVersion, 故直接写字面量.
val mediampVersion = "0.5.0"
val mediampFfmpegIOSRuntime = libs.mediamp.ffmpeg.runtime.ios.xcframework.get()

val mediampFfmpegAppleRuntimeDirectory = layout.buildDirectory.dir("mediamp-ffmpeg/apple-runtime")
val extractMediampFfmpegAppleRuntime = tasks.register<ExtractAppleXcframeworkTask>("extractMediampFfmpegAppleRuntime") {
    archives.from(mediampFfmpegAppleRuntimePath)
    outputDirectory.set(mediampFfmpegAppleRuntimeDirectory)
}

kotlin {
    android {
        namespace = "me.him188.ani.utils.http.downloader"
        packaging {
            resources {
                pickFirsts.add("META-INF/AL2.0")
                pickFirsts.add("META-INF/LGPL2.1")
                excludes.add("META-INF/DEPENDENCIES")
                excludes.add("META-INF/licenses/ASM")
            }
        }
    }
    sourceSets.commonMain.dependencies {
        api(projects.utils.coroutines)
        api(libs.kotlinx.datetime)
        api(libs.mediamp.ffmpeg)
        implementation(projects.utils.logging)
        implementation(projects.utils.ktorClient)
        api(libs.datastore.core)
        implementation(libs.androidx.room.common)
        implementation(projects.utils.serialization)
        implementation(libs.kotlinx.serialization.protobuf)
        implementation(libs.kotlinx.collections.immutable)
    }
    sourceSets.desktopMain.dependencies {
//        runtimeOnly(libs.slf4j.simple)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.turbine)
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.server.test.host)
        runtimeOnly(libs.slf4j.simple)
    }
}

dependencies {
    when (val triple = getOsTriple()) {
        "windows-x64" -> desktopTestRuntimeOnly(libs.mediamp.ffmpeg.runtime.windows.x64)
        "linux-x64" -> desktopTestRuntimeOnly(libs.mediamp.ffmpeg.runtime.linux.x64)
        "macos-x64" -> desktopTestRuntimeOnly(libs.mediamp.ffmpeg.runtime.macos.x64)
        "macos-arm64" -> desktopTestRuntimeOnly(libs.mediamp.ffmpeg.runtime.macos.arm64)
        "windows-arm64" -> desktopTestRuntimeOnly(libs.mediamp.ffmpeg.runtime.windows.arm64)
        else -> throw UnsupportedOperationException("Unknown os: $triple")
    }

    add(
        mediampFfmpegAppleRuntime.name,
        "${mediampFfmpegIOSRuntime.group}:${mediampFfmpegIOSRuntime.name}:$mediampVersion@zip",
    )
}
