package io.xac.systemd.spec;

import java.util.regex.Pattern;

import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * YAML 1.2 标量解析：SnakeYAML 默认按 YAML 1.1 解析，会把 no/on/yes/off 当成布尔值，
 * 这里只保留 true/false、整数、浮点、null 与 merge，让其余标量保持字符串。
 */
public final class Yaml12Resolver extends Resolver {

    public static final Pattern CORE_BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

    @Override
    protected void addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF");
        addImplicitResolver(Tag.INT, INT, "-+0123456789");
        addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
        addImplicitResolver(Tag.MERGE, MERGE, "<");
        addImplicitResolver(Tag.NULL, NULL, "~nN\0");
        addImplicitResolver(Tag.NULL, EMPTY, null);
    }
}
