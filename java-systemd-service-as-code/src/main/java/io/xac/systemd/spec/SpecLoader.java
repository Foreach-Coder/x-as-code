package io.xac.systemd.spec;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.representer.Representer;

/**
 * 从包目录读取 xac.yaml，按 schema 校验后构造 {@link ServiceSpec}。
 * 任何一条 violation 都意味着没有可用的 spec。
 */
public final class SpecLoader {

    public static final class Result {
        public final ServiceSpec spec;
        public final List<Violation> violations;

        public Result(ServiceSpec spec, List<Violation> violations) {
            this.spec = spec;
            this.violations = violations;
        }

        public boolean ok() {
            return spec != null;
        }
    }

    private SpecLoader() {
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                new Yaml12Resolver());
    }

    public static Result load(Path packageDir) {
        Path packageRoot = packageDir.toAbsolutePath().normalize();
        Path file = packageRoot.resolve("xac.yaml");
        List<Violation> violations = new ArrayList<Violation>();
        if (!Files.isRegularFile(file)) {
            violations.add(new Violation("xac.yaml", "包目录下没有 xac.yaml"));
            return new Result(null, violations);
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(new Violation("xac.yaml", "读取失败: " + e.getMessage()));
            return new Result(null, violations);
        }
        Yaml yaml = newYaml();
        Node composed;
        try {
            composed = yaml.compose(new StringReader(text));
        } catch (MarkedYAMLException e) {
            violations.add(new Violation("xac.yaml", "yaml 语法错误: " + e.getProblem()));
            return new Result(null, violations);
        }
        if (composed != null) {
            checkDuplicateKeys(composed, "", violations);
        }
        Object root;
        try {
            root = yaml.load(text);
        } catch (MarkedYAMLException e) {
            violations.add(new Violation("xac.yaml", "yaml 语法错误: " + e.getProblem()));
            return new Result(null, violations);
        }
        if (!(root instanceof Map)) {
            violations.add(new Violation("", "顶层应为映射"));
            return new Result(null, violations);
        }
        Schema.check(root, violations);
        if (!violations.isEmpty()) {
            return new Result(null, violations);
        }
        return new Result(build(root, packageRoot), violations);
    }

    /**
     * SnakeYAML 在 allowDuplicateKeys=true 时会静默用后一个值覆盖，这里先遍历解析树，
     * 用与 schema 一致的点号路径报告重复键。
     */
    private static void checkDuplicateKeys(Node node, String path, List<Violation> out) {
        if (node instanceof MappingNode) {
            Set<String> seen = new LinkedHashSet<String>();
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                Node keyNode = tuple.getKeyNode();
                if (!(keyNode instanceof ScalarNode)) {
                    continue;
                }
                String key = ((ScalarNode) keyNode).getValue();
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (!seen.add(key)) {
                    out.add(new Violation(childPath, "重复字段"));
                }
                checkDuplicateKeys(tuple.getValueNode(), childPath, out);
            }
        } else if (node instanceof SequenceNode) {
            List<Node> items = ((SequenceNode) node).getValue();
            for (int i = 0; i < items.size(); i++) {
                checkDuplicateKeys(items.get(i), path + "[" + i + "]", out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ServiceSpec build(Object root, Path packageRoot) {
        Map<Object, Object> m = (Map<Object, Object>) root;
        Map<Object, Object> artifact = (Map<Object, Object>) m.get("artifact");
        Map<Object, Object> runtime = (Map<Object, Object>) m.get("runtime");
        Map<Object, Object> lifecycle = (Map<Object, Object>) m.get("lifecycle");
        Lifecycle lc = new Lifecycle(
                (Boolean) get(lifecycle, "auto-start"),
                (String) get(lifecycle, "restart"),
                (Integer) get(lifecycle, "restart-sec"),
                (Integer) get(lifecycle, "timeout-stop-sec"));
        String workdirRaw = (String) m.get("workdir");
        Path workdir = workdirRaw == null ? null : packageRoot.resolve(workdirRaw).normalize();
        List<String> opts = new ArrayList<String>();
        Object rawOpts = runtime.get("opts");
        if (rawOpts instanceof List) {
            for (Object o : (List<Object>) rawOpts) {
                opts.add((String) o);
            }
        }
        List<String> pathLinks = new ArrayList<String>();
        Object rawLinks = m.get("path-links");
        if (rawLinks instanceof List) {
            for (Object o : (List<Object>) rawLinks) {
                pathLinks.add((String) o);
            }
        }
        return ServiceSpec.builder(packageRoot)
                .name((String) m.get("name"))
                .description((String) m.get("description"))
                .jar(packageRoot.resolve((String) artifact.get("jar")).normalize())
                .java((String) runtime.get("java"))
                .opts(opts)
                .env(Schema.stringMap(m.get("env")))
                .properties(Schema.stringMap(m.get("properties")))
                .workdir(workdir)
                .pathLinks(pathLinks)
                .lifecycle(lc)
                .build();
    }

    private static Object get(Map<Object, Object> map, String key) {
        return map == null ? null : map.get(key);
    }
}
