package io.xac.systemd.systemd;

import io.xac.systemd.spec.Lifecycle;
import io.xac.systemd.spec.ServiceSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitNamingTest {

    private static final Path PKG = Paths.get("/opt/demo");
    private static final SystemdTarget TARGET =
            new SystemdTarget(Paths.get("/etc/systemd/system"), Paths.get("/etc/xac"));

    private static ServiceSpec spec(String name) {
        return ServiceSpec.builder(PKG)
                .name(name)
                .jar("app.jar")
                .java("/usr/bin/java")
                .opts(new ArrayList<String>(Arrays.asList("-Xmx512m")))
                .lifecycle(new Lifecycle(null, null, null, null))
                .build();
    }

    @Test
    void prefixesUnitAndEnvNames() {
        ServiceSpec spec = spec("demo");

        assertEquals("xac.", UnitNaming.PREFIX);
        assertEquals("xac.demo.service", UnitNaming.unitName(spec));
        assertEquals("xac.demo.env", UnitNaming.envFileName(spec));
        assertEquals(TARGET.unitDir.resolve("xac.demo.service"),
                UnitNaming.unitPath(spec, TARGET));
        assertEquals(TARGET.envDir.resolve("xac.demo.env"),
                UnitNaming.envPath(spec, TARGET));
    }

    @Test
    void keepsDotsInsideName() {
        ServiceSpec spec = spec("a.b");

        assertEquals("xac.a.b.service", UnitNaming.unitName(spec));
        assertEquals("xac.a.b.env", UnitNaming.envFileName(spec));
    }
}
