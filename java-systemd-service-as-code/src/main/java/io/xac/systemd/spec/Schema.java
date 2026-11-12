package io.xac.systemd.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xac.yaml 的结构描述与类型/缺字段检查。键名与 YAML 中的写法一致，错误路径用点号拼接。
 */
public final class Schema {

    public enum Type {
        STRING, BOOLEAN, INTEGER, STRING_LIST, STRING_MAP, SECTION
    }

    public static final class Node {
        public final String name;
        public final Type type;
        public final boolean required;
        public final List<Node> children = new ArrayList<Node>();

        public Node(String name, Type type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }

        public Node child(String childName) {
            for (Node child : children) {
                if (child.name.equals(childName)) {
                    return child;
                }
            }
            return null;
        }
    }

    public static final Node ROOT = section("", true,
            scalar("name", Type.STRING, true),
            scalar("description", Type.STRING, false),
            section("artifact", true,
                    scalar("jar", Type.STRING, true)),
            section("runtime", true,
                    scalar("java", Type.STRING, true),
                    scalar("opts", Type.STRING_LIST, false)),
            scalar("env", Type.STRING_MAP, false),
            scalar("properties", Type.STRING_MAP, false),
            scalar("workdir", Type.STRING, false),
            scalar("path-links", Type.STRING_LIST, false),
            section("logs", false,
                    scalar("file", Type.STRING, true)),
            section("lifecycle", false,
                    scalar("auto-start", Type.BOOLEAN, false),
                    scalar("restart", Type.STRING, false),
                    scalar("restart-sec", Type.INTEGER, false),
                    scalar("timeout-stop-sec", Type.INTEGER, false)));

    private Schema() {
    }

    private static Node scalar(String name, Type type, boolean required) {
        return new Node(name, type, required);
    }

    private static Node section(String name, boolean required, Node... children) {
        Node node = new Node(name, Type.SECTION, required);
        for (Node child : children) {
            node.children.add(child);
        }
        return node;
    }

    public static void check(Object raw, List<Violation> out) {
        checkNode(ROOT, raw, "", out);
    }

    @SuppressWarnings("unchecked")
    private static void checkNode(Node node, Object raw, String path, List<Violation> out) {
        switch (node.type) {
            case SECTION:
                checkSection(node, raw, path, out);
                break;
            case STRING:
                if (raw != null && !(raw instanceof String)) {
                    out.add(new Violation(path, "must be a string"));
                }
                break;
            case BOOLEAN:
                if (raw != null && !(raw instanceof Boolean)) {
                    out.add(new Violation(path, "must be a boolean"));
                }
                break;
            case INTEGER:
                if (raw != null && !(raw instanceof Integer)) {
                    out.add(new Violation(path, "must be an integer"));
                }
                break;
            case STRING_LIST:
                if (raw != null) {
                    if (!(raw instanceof List)) {
                        out.add(new Violation(path, "must be a list of strings"));
                    } else {
                        List<Object> items = (List<Object>) raw;
                        for (int i = 0; i < items.size(); i++) {
                            if (!(items.get(i) instanceof String)) {
                                out.add(new Violation(path + "[" + i + "]", "must be a string"));
                            }
                        }
                    }
                }
                break;
            case STRING_MAP:
                if (raw != null) {
                    if (!(raw instanceof Map)) {
                        out.add(new Violation(path, "must be a mapping"));
                    } else {
                        Map<Object, Object> map = (Map<Object, Object>) raw;
                        for (Map.Entry<Object, Object> e : map.entrySet()) {
                            if (!(e.getKey() instanceof String)) {
                                out.add(new Violation(path, "keys must be strings"));
                                continue;
                            }
                            String key = (String) e.getKey();
                            String childPath = path.isEmpty() ? key : path + "." + key;
                            if (!(e.getValue() instanceof String)) {
                                out.add(new Violation(childPath, "must be a string"));
                            }
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkSection(Node node, Object raw, String path, List<Violation> out) {
        Map<Object, Object> map = null;
        if (raw != null) {
            if (!(raw instanceof Map)) {
                out.add(new Violation(path, "must be a mapping"));
                return;
            }
            map = (Map<Object, Object>) raw;
        }
        for (Node child : node.children) {
            String childPath = path.isEmpty() ? child.name : path + "." + child.name;
            boolean present = map != null && map.containsKey(child.name);
            if (!present) {
                if (child.required) {
                    out.add(new Violation(childPath, "missing required field"));
                }
                continue;
            }
            Object value = map.get(child.name);
            if (value == null) {
                if (child.type == Type.SECTION) {
                    // `logs:` 这样的空段按「空映射」处理，让子字段的必填错误带上精确路径。
                    checkSection(child, new LinkedHashMap<Object, Object>(), childPath, out);
                } else if (child.required) {
                    out.add(new Violation(childPath, "missing required field"));
                }
                continue;
            }
            checkNode(child, value, childPath, out);
        }
        if (map != null) {
            for (Object key : map.keySet()) {
                if (!(key instanceof String) || node.child((String) key) == null) {
                    String keyPath = path.isEmpty() ? String.valueOf(key) : path + "." + key;
                    out.add(new Violation(keyPath, "unknown field"));
                }
            }
        }
    }

    /** 从已通过类型检查的原始结构构造有序字符串映射。 */
    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, String> stringMap(Object raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (raw instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) raw;
            for (Map.Entry<Object, Object> e : map.entrySet()) {
                out.put((String) e.getKey(), (String) e.getValue());
            }
        }
        return out;
    }
}
