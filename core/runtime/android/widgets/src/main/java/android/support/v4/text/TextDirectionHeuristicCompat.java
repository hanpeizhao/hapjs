/*
 * Copyright (c) 2026, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package android.support.v4.text;

/**
 * support-v4 TextDirectionHeuristicCompat 的最小 shim 接口（本工程内提供，
 * 见 widgets/build.gradle 说明），签名与 support-v4 27/28 保持一致，仅服务于
 * textlayoutbuilder 1.4.0 的运行期依赖。
 */
public interface TextDirectionHeuristicCompat {
    boolean isRtl(CharSequence array, int start, int count);

    boolean isRtl(char[] array, int start, int count);
}
