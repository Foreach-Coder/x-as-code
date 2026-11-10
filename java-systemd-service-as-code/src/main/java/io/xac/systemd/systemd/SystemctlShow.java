package io.xac.systemd.systemd;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;

public final class SystemctlShow {

    /**
     * 一次 {@code systemctl show} 的结果。ok=false 表示查询本身失败，
     * values 为空——不能当成「未启用」或「未运行」来解释。
     */
    public static final class Query {
        public final boolean ok;
        public final Map<String, String> values;

        private Query(boolean ok, Map<String, String> values) {
            this.ok = ok;
            this.values = values;
        }
    }

    private static final List<String> PROPERTIES = Arrays.asList(
            "LoadState", "ActiveState", "SubState", "UnitFileState",
            "MainPID", "ActiveEnterTimestamp", "FragmentPath");

    public static Query query(CommandRunner runner, String unitName) {
        StringBuilder property = new StringBuilder("--property=");
        for (int i = 0; i < PROPERTIES.size(); i++) {
            if (i > 0) {
                property.append(',');
            }
            property.append(PROPERTIES.get(i));
        }
        ExecResult result = runner.run(
                Arrays.asList("systemctl", "show", unitName, property.toString()));
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (!result.ok()) {
            return new Query(false, values);
        }
        for (String line : result.stdout.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return new Query(true, values);
    }

    private SystemctlShow() {
    }
}
