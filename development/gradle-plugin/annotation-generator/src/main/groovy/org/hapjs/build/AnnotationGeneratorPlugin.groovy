/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 注解生成插件：application 模块在 Java 编译前根据合并后的 assets（card.json 等）
 * 生成卡片元数据 Java 源码。旧版针对 library 模块的 Proguard 任务钩子在
 * AGP 3.6+ 后已失效，AGP 9 起彻底移除相关逻辑。
 */
class AnnotationGeneratorPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // Gradle 9：buildDir 属性已移除，改用 layout.buildDirectory
        def buildDir = project.layout.buildDirectory.get().asFile
        // 旧版通过 'apk' configuration 判断 application 模块，该配置在新 AGP 中已移除
        if (project.plugins.hasPlugin('com.android.application')) {
            def javaOutputDir = new File(buildDir, "generated/hap/src/main/java")
            project.android.sourceSets.main.java.srcDirs += javaOutputDir

            project.afterEvaluate {
                // AGP 9：旧 Variant API（applicationVariants）已移除，
                // 改为按任务名匹配 compile*JavaWithJavac 挂接生成逻辑
                project.tasks.matching { it.name ==~ /compile\w+JavaWithJavac/ }.configureEach { compileTask ->
                    def matcher = compileTask.name =~ /compile(\w+)JavaWithJavac/
                    def variantName = matcher[0][1]
                    compileTask.dependsOn project.tasks.matching {
                        it.name == "merge${variantName}Assets"
                    }
                    compileTask.doFirst {
                        def mergedAssetsDir = new File(buildDir,
                                "intermediates/merged_assets/${variantName}/out/hap")
                        if (!mergedAssetsDir.exists()) {
                            mergedAssetsDir = new File(buildDir,
                                    "intermediates/merged_assets/${variantName}/merge${variantName}Assets/out/hap")
                        }
                        if (!mergedAssetsDir.exists()) {
                            // AGP 9：merged_assets 更名为 assets，合并输出直接位于
                            // merge<Variant>Assets/hap（不再有 out 子层）
                            mergedAssetsDir = new File(buildDir,
                                    "intermediates/assets/${variantName}/merge${variantName}Assets/hap")
                        }
                        def cardJsonFile = new File(buildDir,
                                "intermediates/merged_assets/${variantName}/out/hap/card.json")
                        if (!cardJsonFile.exists()) {
                            cardJsonFile = null
                        }
                        def processor = new JavaResourceProcessor(mergedAssetsDir, javaOutputDir, cardJsonFile)
                        processor.process()
                    }
                }
            }
        }
    }
}
