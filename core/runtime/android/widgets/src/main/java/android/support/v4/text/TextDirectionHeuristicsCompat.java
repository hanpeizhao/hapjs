/*
 * Copyright (c) 2026, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package android.support.v4.text;

/**
 * support-v4 TextDirectionHeuristicsCompat 的最小 shim 实现（本工程内提供，
 * 见 widgets/build.gradle 说明）。
 *
 * staticlayout-proxy 1.4.0 的 fromTextDirectionHeuristicCompat 通过“引用相等”
 * 逐一比对下列静态常量并映射到 framework 的 TextDirectionHeuristics，因此各
 * 常量必须是独立对象，语义直接委托 framework 实现（API 18+，minSdk 21 满足）。
 */
public final class TextDirectionHeuristicsCompat {
    public static final TextDirectionHeuristicCompat LTR = new Heuristic(
            android.text.TextDirectionHeuristics.LTR);
    public static final TextDirectionHeuristicCompat RTL = new Heuristic(
            android.text.TextDirectionHeuristics.RTL);
    public static final TextDirectionHeuristicCompat FIRSTSTRONG_LTR = new Heuristic(
            android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR);
    public static final TextDirectionHeuristicCompat FIRSTSTRONG_RTL = new Heuristic(
            android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL);
    public static final TextDirectionHeuristicCompat ANYRTL_LTR = new Heuristic(
            android.text.TextDirectionHeuristics.ANYRTL_LTR);
    public static final TextDirectionHeuristicCompat LOCALE = new Heuristic(
            android.text.TextDirectionHeuristics.LOCALE);

    private TextDirectionHeuristicsCompat() {
    }

    private static class Heuristic implements TextDirectionHeuristicCompat {
        private final android.text.TextDirectionHeuristic mDelegate;

        Heuristic(android.text.TextDirectionHeuristic delegate) {
            mDelegate = delegate;
        }

        @Override
        public boolean isRtl(CharSequence array, int start, int count) {
            return mDelegate.isRtl(array, start, count);
        }

        @Override
        public boolean isRtl(char[] array, int start, int count) {
            return mDelegate.isRtl(array, start, count);
        }
    }
}
