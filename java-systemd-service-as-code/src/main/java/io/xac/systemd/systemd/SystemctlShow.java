package io.xac.systemd.systemd;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.xac.systemd.exec.CommandRunner;
import io.xac.systemd.exec.ExecResult;

public final class SystemctlShow {

    private static final List<String> PROPERTIES = Arrays.asList(
            "LoadState", "ActiveState", "SubState", "UnitFileState",
            "MainPID", "ActiveEnterTimestamp", "FragmentPath");

    public static Map<String, String> show(CommandRunner runner, String unitName) {
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
            return values;
        }
        for (String line : result.stdout.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    private SystemctlShow() {
    }
}
