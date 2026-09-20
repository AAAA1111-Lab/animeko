/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.gradle.api.tasks.PathSensitivity

plugins {
    id("ani.kmp-compose")
    alias(libs.plugins.kotlin.plugin.serialization)

    // org.jetbrains.kotlinx.atomicfu
    idea
}

kotlin {
    android {
        namespace = "me.him188.ani.app.ui.lang"
    }
    sourceSets.commonMain.dependencies {
        implementation(libs.atomicfu)
        api(libs.compose.components.resources)
    }
    sourceSets.commonTest.dependencies {
    }
    sourceSets.androidMain.dependencies {
    }
    sourceSets.desktopMain.dependencies {
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "me.him188.ani.app.ui.lang"
    customDirectory(
        "commonMain",
        project.provider {
            project.layout.projectDirectory.dir("src/androidMain/res")
        },
    )
}

// Compose 资源生成只从 `customDirectory` 里读取字符串, 而该目录是 `src` 下的源目录.
// 这里额外把整棵 res 目录显式声明为这些任务的输入, 保证改动 strings*.xml 后它们一定重新执行,
// 不会被"最新检查"/构建缓存跳过而留下过期的资源访问器 (Res.string.xxx 解析不到).
val stringResourceSourceDir = layout.projectDirectory.dir("src/androidMain/res")

val populateStringsLocales by tasks.registering(Copy::class) {
    group = "ani"
    description =
        "Populate string resources for locales speaking Simplified or Traditional Chinese."

    val chtLocales = listOf(
        "values-zh-rMO",
    )
    val chsLocales = listOf(
        "values-zh",
        "values-zh-rSG",
    )
    destinationDir = file("src/androidMain/res")

    for (file in file("src/androidMain/res/values").listFiles().orEmpty()) {
        if (file.isFile && file.name.startsWith("strings") && file.extension == "xml") {
            for (locale in chtLocales) {
                from(file("src/androidMain/res/values-zh-rHK/${file.name}")) {
                    into(locale)
                    rename { file.name }
                }
            }

            for (locale in chsLocales) {
                from(file("src/androidMain/res/values-zh-rCN/${file.name}")) {
                    into(locale)
                    rename { file.name }
                }
            }
        }
    }

}

tasks.matching {
    // desktop, `generateResourceAccessorsForCommonMain`
    it.name.startsWith("convertXmlValueResources")
            || it.name.startsWith("copyNonXmlValueResources")
            // android, `generateDebugResources`
            || (it.name.startsWith("generate") && it.name.endsWith("Resources"))
            || it.name.startsWith("extractDeepLinks")
            || (it.name.startsWith("map") && it.name.endsWith("SourceSetPaths")) // mapReleaseSourceSetPaths
}.configureEach {
    dependsOn(populateStringsLocales)
}

// 资源访问器生成的输入是 `customDirectory` (src/androidMain/res) 下的 XML. 该目录由
// populateStringsLocales 写入, 所以这里既把它声明为显式输入, 又保证排在写入之后.
// 另外显式关掉最新检查: 这几个任务本身不用构建缓存 (Caching disabled), 一旦被判定为"最新",
// 就会沿用上一轮(甚至是别的 commit)生成的访问器, 新加的字符串不会出现在 Res.string 里,
// 编译期报 `Unresolved reference: xxx` 而源码完全正确. 它们只读 XML 做文本生成, 重跑代价很小.
val stringResourceTasks = tasks.matching {
    it.name.startsWith("convertXmlValueResources")
            || it.name.startsWith("copyNonXmlValueResources")
            || it.name.startsWith("prepareComposeResourcesTask")
            || it.name.startsWith("generateResourceAccessors")
}
stringResourceTasks.configureEach {
    inputs.dir(stringResourceSourceDir)
        .withPropertyName("aniStringResourceSourceDir")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    mustRunAfter(populateStringsLocales)
}
// 只对"生成访问器"这一步关掉最新检查: 它决定 Res.string 里有哪些条目, 一旦跳过就会沿用旧结果.
stringResourceTasks.matching { it.name.startsWith("generateResourceAccessors") }.configureEach {
    outputs.upToDateWhen { false }
}

idea {
    module {
        excludeDirs.add(file("src/androidMain/res/values-zh"))
        excludeDirs.add(file("src/androidMain/res/values-zh-rMO"))
        excludeDirs.add(file("src/androidMain/res/values-zh-rSG"))
    }
}
