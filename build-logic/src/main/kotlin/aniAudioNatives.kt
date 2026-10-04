/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Android 端音频 native 库 (WSOLA 变速 libani_wsola 与 FLAC 软解 libani_flac) 的构建.
 *
 * 用 NDK 的 clang++ 把模块 `src/androidMain/cpp` 下的源码按 ABI 各编成一个 .so,
 * 再经 variant jniLibs 注入. 找不到 NDK 或设置 `ani.audio.natives.skip=true` 时跳过:
 * FLAC 回退平台解码器, WSOLA 处理器回退 Sonic.
 *
 * JNI 符号前缀与 Kotlin 侧 `me.him188.ani.app.video.player.media.audio` 包名绑定, 改包名必须同步改 cpp.
 */
private data class AniNativeAudioLibrary(
    val sources: List<String>,
    val libraryName: String,
    val taskInfix: String,
    val includeDirs: List<String> = emptyList(),
    val thirdPartyIncludeDirs: List<String> = emptyList(),
)

private val aniNativeAudioLibraries = listOf(
    AniNativeAudioLibrary(
        sources = listOf("wsola/scaletempo2.cpp", "wsola/wsola_jni.cpp"),
        libraryName = "ani_wsola",
        taskInfix = "Wsola",
    ),
    AniNativeAudioLibrary(
        sources = listOf("flac/flac_decode.cpp", "flac/flac_jni.cpp", "flac/dr_flac_impl.cpp"),
        libraryName = "ani_flac",
        taskInfix = "Flac",
        includeDirs = listOf("flac"),
        thirdPartyIncludeDirs = listOf("flac/thirdparty"),
    ),
)

// NDK 的 per-ABI clang++ 包装器名称 = triple + min API level (如 aarch64-linux-android21-clang++),
// 用 API 21 编出的产物向后兼容所有更高 minSdk.
private val aniNativeAudioAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android21",
    "armeabi-v7a" to "armv7a-linux-androideabi21",
    "x86_64" to "x86_64-linux-android21",
    "x86" to "i686-linux-android21",
)

/**
 * 编译 [AniCompileNativeAudioTask] 声明的 `.cpp` 为一个 .so. 每个 ABI 一个任务.
 */
abstract class AniCompileNativeAudioTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: RegularFileProperty

    @get:Input
    abstract val compilerArgs: ListProperty<String>

    @get:Input
    abstract val windowsHost: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        val sourceFiles = sources.files.filter { it.extension == "cpp" }.sortedBy { it.name }
        require(sourceFiles.isNotEmpty()) { "No .cpp sources configured for ${outputFile.get().asFile}" }
        val launcher = if (windowsHost.get()) listOf("cmd.exe", "/d", "/c") else emptyList()
        val command = launcher + compiler.get().asFile.absolutePath + compilerArgs.get() +
                sourceFiles.map { it.absolutePath } + listOf("-o", out.absolutePath)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Native compile failed (exit $exitCode): ${command.joinToString(" ")}\n$output")
        }
        logger.info(output)
    }
}

/**
 * 把各 ABI 的编译产物整理成 jniLibs 要求的 `<output>/<abi>/lib*.so` 目录布局.
 *
 * 不能用 `project.sync`: 任务执行期触碰 project 会破坏 configuration cache.
 */
abstract class AniPrepareNativeAudioJniLibsTask : DefaultTask() {
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        inputFiles.files.sortedBy { it.parentFile.name }.forEach { library ->
            val abiDir = File(target, library.parentFile.name)
            abiDir.mkdirs()
            library.copyTo(File(abiDir, library.name), overwrite = true)
        }
    }
}

private fun resolveAniAndroidNdkDir(project: Project): File? {
    System.getenv("ANDROID_NDK_HOME")?.let { envDir ->
        val dir = File(envDir)
        if (dir.isDirectory) return dir
    }
    val props = java.util.Properties()
    val localPropertiesFile = project.rootDir.resolve("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { props.load(it) }
    }
    props.getProperty("ndk.dir")?.let { ndkDir ->
        val dir = File(ndkDir)
        if (dir.isDirectory) return dir
    }
    val sdkDir = sequenceOf(props.getProperty("sdk.dir"), System.getenv("ANDROID_HOME"))
        .mapNotNull { path -> path?.let(::File)?.takeIf { it.isDirectory } }
        .firstOrNull()
        ?: return null
    return sdkDir.resolve("ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

/**
 * 在 KMP android 模块上注册音频 native 库的编译任务并注入 jniLibs.
 * 模块需包含 `src/androidMain/cpp` 下的源码 (见 [aniNativeAudioLibraries]).
 */
fun Project.configureAniAudioNatives() {
    if (findProperty("ani.audio.natives.skip")?.toString()?.toBoolean() == true) {
        logger.lifecycle("Skipping ani audio native builds: ani.audio.natives.skip=true")
        return
    }
    val ndkDir = resolveAniAndroidNdkDir(this)
    if (ndkDir == null) {
        logger.warn(
            "Android NDK not found - skipping ani audio native builds (WSOLA/FLAC software decode). " +
                    "Set ANDROID_NDK_HOME or sdk.dir/ndk.dir to enable.",
        )
        return
    }
    val hostTag = when (getOs()) {
        Os.Windows -> "windows-x86_64"
        Os.MacOS -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val llvmBinDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    check(llvmBinDir.isDirectory) { "NDK LLVM toolchain not found at '$llvmBinDir'." }
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")
    val windowsHost = getOs() == Os.Windows

    val compileTasks = mutableListOf<TaskProvider<AniCompileNativeAudioTask>>()
    for (library in aniNativeAudioLibraries) {
        for ((abi, clangTriple) in aniNativeAudioAbis) {
            compileTasks += tasks.register<AniCompileNativeAudioTask>("compile${library.taskInfix}${abi.replace("-", "")}") {
                group = "ani-audio-natives"
                description = "Compile lib${library.libraryName}.so for Android $abi"
                val compilerSuffix = if (windowsHost) ".cmd" else ""
                compiler.set(llvmBinDir.resolve("$clangTriple-clang++$compilerSuffix"))
                this.windowsHost.set(windowsHost)
                val includeArgs = library.includeDirs.flatMap {
                    listOf("-I", layout.projectDirectory.dir("src/androidMain/cpp/$it").asFile.absolutePath)
                } + library.thirdPartyIncludeDirs.flatMap {
                    listOf("-isystem", layout.projectDirectory.dir("src/androidMain/cpp/$it").asFile.absolutePath)
                }
                compilerArgs.set(
                    listOf(
                        "--sysroot=${sysroot.absolutePath}",
                        "-std=c++17",
                        "-O2",
                        "-DNDEBUG",
                        "-Wall",
                        "-Wextra",
                        "-fPIC",
                        "-shared",
                        "-Wl,-z,max-page-size=16384",
                        "-llog",
                    ) + includeArgs,
                )
                sources.from(library.sources.map { layout.projectDirectory.file("src/androidMain/cpp/$it") })
                outputFile.set(layout.buildDirectory.file("generated/ani-audio-jniLibs/$abi/lib${library.libraryName}.so"))
            }
        }
    }

    val prepareTask = tasks.register<AniPrepareNativeAudioJniLibsTask>("prepareAniAudioNativeJniLibs") {
        group = "ani-audio-natives"
        description = "Prepare merged ani audio jniLibs directory for Android variants"
        dependsOn(compileTasks)
        inputFiles.from(compileTasks.map { it.flatMap(AniCompileNativeAudioTask::outputFile) })
        outputDir.set(layout.buildDirectory.dir("generated/ani-audio-jniLibs-merged"))
    }

    val androidComponents = extensions.findByName("androidComponents")
        as? KotlinMultiplatformAndroidComponentsExtension
    androidComponents?.onVariants(androidComponents.selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTask,
            AniPrepareNativeAudioJniLibsTask::outputDir,
        )
    }
}
