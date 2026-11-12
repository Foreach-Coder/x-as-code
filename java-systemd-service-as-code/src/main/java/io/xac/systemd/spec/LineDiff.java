package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行级 diff：给编辑器的保存预览用。
 *
 * <p>按 {@code \n} 切行（行尾的 {@code \r} 丢掉，CRLF 文件不会整篇报改动），
 * 忽略文本末尾的空行；输出用固定前缀 {@code "  "}（不变）、{@code "- "}（删除）、
 * {@code "+ "}（新增），并且始终保持「先删后增」的顺序。同一份输入永远得到同一个输出。</p>
 *
 * <p>实现是 LCS：先剥掉公共前后缀，只在中间段做动态规划，因此日常的小改动几乎不占内存。
 * 中间段超过 {@value #MAX_CELLS} 个格子时退回「整段先删后增」的粗粒度结果——语义仍然正确
 * （显示的改动是真实改动的超集），只是不再逐行配对。{@code < 5000} 行的配置文件不会走到这条路径。</p>
 */
public final class LineDiff {

    /** 不变行前缀。 */
    public static final String SAME = "  ";
    /** 删除行前缀。 */
    public static final String DELETE = "- ";
    /** 新增行前缀。 */
    public static final String INSERT = "+ ";

    /** 中间段 DP 的格子上限，防止病态输入把堆吃光。 */
    private static final long MAX_CELLS = 4000000L;

    private LineDiff() {
    }

    /**
     * 比较两份文本，返回带前缀的行列表；两份都为空时返回空列表。
     */
    public static List<String> diff(String oldText, String newText) {
        List<String> before = lines(oldText);
        List<String> after = lines(newText);
        List<String> out = new ArrayList<String>(before.size() + after.size());

        int prefix = 0;
        int maxPrefix = Math.min(before.size(), after.size());
        while (prefix < maxPrefix && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        int maxSuffix = Math.min(before.size(), after.size()) - prefix;
        while (suffix < maxSuffix
                && before.get(before.size() - 1 - suffix)
                .equals(after.get(after.size() - 1 - suffix))) {
            suffix++;
        }

        for (int i = 0; i < prefix; i++) {
            out.add(SAME + before.get(i));
        }
        List<String> left = before.subList(prefix, before.size() - suffix);
        List<String> right = after.subList(prefix, after.size() - suffix);
        appendChanges(out, left, right);
        for (int i = before.size() - suffix; i < before.size(); i++) {
            out.add(SAME + before.get(i));
        }
        return Collections.unmodifiableList(out);
    }

    private static void appendChanges(List<String> out, List<String> left, List<String> right) {
        int n = left.size();
        int m = right.size();
        if (n == 0) {
            for (String line : right) {
                out.add(INSERT + line);
            }
            return;
        }
        if (m == 0) {
            for (String line : left) {
                out.add(DELETE + line);
            }
            return;
        }
        if ((long) n * m > MAX_CELLS) {
            for (String line : left) {
                out.add(DELETE + line);
            }
            for (String line : right) {
                out.add(INSERT + line);
            }
            return;
        }

        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (left.get(i).equals(right.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).equals(right.get(j))) {
                out.add(SAME + left.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                // 平局时先删后增，保证同一对改动块的顺序稳定。
                out.add(DELETE + left.get(i));
                i++;
            } else {
                out.add(INSERT + right.get(j));
                j++;
            }
        }
        while (i < n) {
            out.add(DELETE + left.get(i));
            i++;
        }
        while (j < m) {
            out.add(INSERT + right.get(j));
            j++;
        }
    }

    /**
     * 按 {@code \n} 切行，去掉每行末尾的 {@code \r}，并丢弃文本末尾的空行。
     * 文本中间的空白行保留，因为它们可能承载段落分隔。
     */
    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(stripCr(text.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(stripCr(text.substring(start)));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
