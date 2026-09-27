/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

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
    }
}
