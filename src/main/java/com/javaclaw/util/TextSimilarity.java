package com.javaclaw.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文本相似度工具：双字母组（bigram）多重集合的 Jaccard 相似度，取值 [0.0, 1.0]。
 *
 * <p>循环子系统「无进展检测」与 {@code agent.hook.LoopDetectionHook} 的「重复调用检测」
 * 共用此单一实现，两处不再各自维护拷贝。</p>
 */
public final class TextSimilarity {

    private TextSimilarity() {}

    /**
     * 计算两段文本的相似度。
     *
     * @param a 文本 A（null 视为空串）
     * @param b 文本 B（null 视为空串）
     * @return 相似度 [0.0, 1.0]，1.0 表示完全相同
     */
    public static double bigramJaccard(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        // 过短时退化为精确比较（不足以构成一个双字母组）
        if (x.length() < 2 || y.length() < 2) {
            return x.equals(y) ? 1.0 : 0.0;
        }
        Map<String, Integer> bigramsA = buildBigramMap(x);
        Map<String, Integer> bigramsB = buildBigramMap(y);

        int intersection = 0;
        int union = 0;
        Set<String> all = new HashSet<>(bigramsA.keySet());
        all.addAll(bigramsB.keySet());
        for (String bigram : all) {
            int countA = bigramsA.getOrDefault(bigram, 0);
            int countB = bigramsB.getOrDefault(bigram, 0);
            intersection += Math.min(countA, countB);
            union += Math.max(countA, countB);
        }
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static Map<String, Integer> buildBigramMap(String s) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < s.length() - 1; i++) {
            map.merge(s.substring(i, i + 2), 1, Integer::sum);
        }
        return map;
    }
}
