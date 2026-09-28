/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.build

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 注解执行插件：为每个 Android 模块的每个 variant 配置 annotation-processor 的
 * outputDir 参数，使编译期注解处理生成的 hap 元数据 assets 能被打进模块。
 *
 * 生成目录按 variant 隔离（generated/hap/<variant>/assets）：AGP 对自身生成物
 * （generated/res/...）同样按 variant 隔离。共享同一物理目录会导致跨变体的
 * 隐式依赖——CI 一次任务图同时构建 assemble<Tv|Phone>Debug 与 Release 时，
 * mergeTvDebugAssets 会"使用"compileTvReleaseJavaWithJavac 声明的输出目录，
 * 触发 Gradle 9 的 implicit dependency 校验直接失败。
 */
class AnnotationExecutorPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // Gradle 9：buildDir 属性已移除，改用 layout.buildDirectory
        def generatedRoot = new File(project.layout.buildDirectory.get().asFile, "generated/hap")

        project.android.compileOptions.sourceCompatibility = JavaVersion.VERSION_1_8
        project.android.compileOptions.targetCompatibility = JavaVersion.VERSION_1_8

        def components = project.extensions.findByType(AndroidComponentsExtension)
        components.onVariants(components.selector().all()) { variant ->
            // variant.name 首字母小写（如 tvDebug）；对应任务名首字母大写（CompileTvDebug...）
            def variantAssetsDir = new File(generatedRoot, "${variant.name}/assets")
            def variantMetadataDir = new File(variantAssetsDir, "hap/" + project.name)
            // 注册为本 variant 的 assets 源目录，merge<Variant>Assets 仅汇总本变体目录
            variant.sources.assets?.addStaticSourceDirectory(variantAssetsDir.absolutePath)
            variant.configureJavaCompileTask { compileTask ->
                // 注解处理器 outputDir 参数按 variant 注入（javac 的 -A 开关）。
                // 经 configureJavaCompileTask 注入保证时序：AGP 完成任务自身接线后执行，
                // 不会被 AGP 的 DSL 配置覆盖
                compileTask.options.compilerArgs.add("-AoutputDir=${variantMetadataDir.absolutePath}")
                // 元数据 JSON 属于 compile 任务的副作用，声明为附加输出后，
                // build cache 命中（FROM-CACHE）时 JSON 会随产物一并恢复，
                // 否则下游 merge assets 元数据丢失（秒开链路断裂，见 doc/PROJECT_GUIDE.md 3.8）
                compileTask.outputs.dir(variantAssetsDir)
            }
        }

        // merge assets 的输入含本 variant 生成目录，须显式依赖同 variant 的 compile 任务，
        // 既保证 JSON 先于合并生成，也满足 Gradle 9 的任务竞态（implicit dependency）校验。
        // application 模块除外：其 compile 已被 generator 插件依赖 merge assets（扫描合并
        // 结果生成 DependencyManagerImpl），再反向依赖会成环
        if (!project.plugins.hasPlugin('com.android.application')) {
            project.tasks.matching { it.name ==~ /merge\w+Assets/ }.configureEach { mergeTask ->
                def variantName = (mergeTask.name =~ /merge(\w+)Assets/)[0][1]
                mergeTask.dependsOn project.tasks.matching {
                    it.name == "compile${variantName}JavaWithJavac"
                }
            }
        }
    }
}
