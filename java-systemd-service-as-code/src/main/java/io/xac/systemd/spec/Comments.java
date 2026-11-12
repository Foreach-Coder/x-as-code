package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从既有 {@code xac.yaml} 中抽取出的注释，按规范化路径索引。
 *
 * <p>路径与 {@link YamlCommentScanner} 的缩进栈一致，例如 {@code name}、{@code artifact.jar}、
 * {@code runtime.opts[-Xmx512m]}、{@code path-links[./logs:/applog/demo]}、{@code env.KEY}。
 * 列表项按<strong>值</strong>索引，因此列表重排后注释仍跟着对应元素走。</p>
 *
 * <p>{@link #orphans} 保存无法归属的注释，每条形如 {@code line 7: text}；重复列表值的第二份
 * 注释也记在这里（{@code duplicate comment for <path>: text}），供编辑器在 diff 前提示
 * 「N 条注释无法定位」。</p>
 *
 * <p>注释文本在扫描时已 trim；写入前 {@link YamlCommentScanner#apply} 会把 CR/LF/NEL/LS/PS
 * 转义成可见文本，因此这里的值可以来自程序化构造，不会注入额外配置行。</p>
 */
public final class Comments {

    /** 路径 → 同行注释文本（不含 {@code #}，已 trim）。 */
    public final Map<String, String> inline;

    /** 路径 → 紧邻其上的注释行（不含 {@code #}，已 trim，保持原顺序）。 */
    public final Map<String, List<String>> leading;

    /** 无法归属的注释，含 1 起的行号前缀。 */
    public final List<String> orphans;

    public Comments(Map<String, String> inline,
                    Map<String, List<String>> leading,
                    List<String> orphans) {
        this.inline = Collections.unmodifiableMap(new LinkedHashMap<String, String>(inline));
        LinkedHashMap<String, List<String>> leadingCopy =
                new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : leading.entrySet()) {
            leadingCopy.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
        }
        this.leading = Collections.unmodifiableMap(leadingCopy);
        this.orphans = Collections.unmodifiableList(new ArrayList<String>(orphans));
    }

    /** 没有任何注释的扫描结果。 */
    public static Comments empty() {
        return new Comments(Collections.<String, String>emptyMap(),
                Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList());
    }

    /** 注释行总数：同行注释逐条计，前置注释逐行计，游离注释逐条计。 */
    public int total() {
        int total = inline.size() + orphans.size();
        for (List<String> block : leading.values()) {
            total += block.size();
        }
        return total;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Comments)) {
            return false;
        }
        Comments that = (Comments) other;
        return inline.equals(that.inline)
                && leading.equals(that.leading)
                && orphans.equals(that.orphans);
    }

    @Override
    public int hashCode() {
        int result = inline.hashCode();
        result = 31 * result + leading.hashCode();
        result = 31 * result + orphans.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Comments{inline=" + inline + ", leading=" + leading + ", orphans=" + orphans + '}';
    }
}
