/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

/**
 * 注解执行插件：为每个 Android 模块配置 annotation-processor 的 outputDir 参数，
 * 使编译期注解处理生成的 hap 元数据 assets 能被打进模块。
 */
class AnnotationExecutorPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // Gradle 9：buildDir 属性已移除，改用 layout.buildDirectory
        def generatedAssetsDir = new File(project.layout.buildDirectory.get().asFile, "generated/hap/src/main/assets")
        def generatedMetadataDir = new File(generatedAssetsDir, "hap/" + project.name)

        project.android.defaultConfig.javaCompileOptions.annotationProcessorOptions.arguments =
                [ outputDir :  generatedMetadataDir.absolutePath]
        project.android.compileOptions.sourceCompatibility = JavaVersion.VERSION_1_8
        project.android.compileOptions.targetCompatibility = JavaVersion.VERSION_1_8
        project.android.sourceSets.main.assets.srcDirs += generatedAssetsDir

        // 注解处理器把元数据 JSON 写入 generatedAssetsDir 属于 compile 任务的副作用，
        // 不在 JavaCompile 默认声明的 outputs 内：build cache 命中（FROM-CACHE）时 class
        // 产物会恢复而这些 JSON 不会，下游 merge assets 元数据随之丢失（秒开链路断裂）。
        // 声明为 compile 任务附加输出后，缓存命中时 JSON 会随产物一并恢复
        project.tasks.withType(JavaCompile).configureEach { compileTask ->
            compileTask.outputs.dir(generatedAssetsDir)
        }
        // merge assets 的输入含 generatedAssetsDir，须显式依赖同 variant 的 compile 任务，
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
