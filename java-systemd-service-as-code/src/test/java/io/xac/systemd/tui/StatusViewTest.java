package io.xac.systemd.tui;

import io.xac.systemd.core.StatusReport;
import io.xac.systemd.status.StatusResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态区四行的纯渲染契约：顺序、漂移标记、unknown 收口。
 */
class StatusViewTest {

    private static Map<String, String> show(String activeState, String mainPid) {
        return show(activeState, mainPid, "enabled");
    }

    private static Map<String, String> show(String activeState, String mainPid,
                                            String unitFileState) {
        Map<String, String> show = new LinkedHashMap<String, String>();
        show.put("LoadState", "loaded");
        show.put("ActiveState", activeState);
        show.put("SubState", "running");
        show.put("UnitFileState", unitFileState);
        show.put("MainPID", mainPid);
        return show;
    }

    /** 全字段构造器，给漂移矩阵用；enabled 始终由 show 的 UnitFileState 派生。 */
    private static StatusResult full(boolean unitInstalled, boolean unitInSync,
                                     boolean envConfigured, boolean envInstalled,
                                     boolean envReadable, boolean envInSync,
                                     Map<String, String> show, boolean queryOk,
                                     boolean wantEnabled, List<String> linkProblems) {
        boolean enabled = "enabled".equals(show.get("UnitFileState"));
        return new StatusResult("xac.demo", Paths.get("/srv/demo"),
                Paths.get("/etc/systemd/system/xac.demo.service"), unitInstalled, unitInSync,
                Paths.get("/etc/xac/demo.env"), envConfigured, envInstalled, envReadable, envInSync,
                show, queryOk, enabled, wantEnabled, linkProblems);
    }

    private static StatusResult result(boolean unitInSync, boolean envReadable, boolean envInSync,
                                       boolean queryOk, Map<String, String> show,
                                       List<String> linkProblems) {
        return new StatusResult("xac.demo", Paths.get("/srv/demo"),
                Paths.get("/etc/systemd/system/xac.demo.service"), true, unitInSync,
                Paths.get("/etc/xac/demo.env"), true, true, envReadable, envInSync,
                show, queryOk, true, true, linkProblems);
    }

    private static StatusReport report(StatusResult status) {
        return new StatusReport(status, status.drift(), status.healthy());
    }

    private static List<String> healthyLines() {
        StatusResult status = result(true, true, true, true, show("active", "4321"),
                Collections.<String>emptyList());
        return StatusView.lines(report(status));
    }

    @Test
    void rendersUnitEnvProcessLinksInOrder() {
        List<String> lines = healthyLines();

        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("unit"), lines.get(0));
        assertTrue(lines.get(0).contains("in sync"), lines.get(0));
        assertTrue(lines.get(0).contains("xac.demo.service"), lines.get(0));
        assertTrue(lines.get(1).contains("env"), lines.get(1));
        assertTrue(lines.get(1).contains("in sync"), lines.get(1));
        assertTrue(lines.get(2).contains("process"), lines.get(2));
        assertTrue(lines.get(2).contains("active"), lines.get(2));
        assertTrue(lines.get(2).contains("pid 4321"), lines.get(2));
        assertTrue(lines.get(3).contains("links"), lines.get(3));
        assertTrue(lines.get(3).contains("ok"), lines.get(3));
        for (String line : lines) {
            assertFalse(line.startsWith("! "), line);
        }
    }

    @Test
    void marksOutOfSyncUnitWithBangPrefix() {
        StatusResult status = result(false, true, true, true, show("active", "4321"),
                Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(0).startsWith("! "), lines.get(0));
        assertTrue(lines.get(0).contains("out of sync"), lines.get(0));
        assertTrue(lines.get(1).startsWith("  "), lines.get(1));
    }

    @Test
    void unreadableEnvIsCannotCheckWithoutDrift() {
        StatusResult status = result(true, false, false, true, show("active", "4321"),
                Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(1).contains("cannot check"), lines.get(1));
        assertFalse(lines.get(1).startsWith("! "), lines.get(1));
    }

    @Test
    void queryFailureShowsUnknownProcessRow() {
        StatusResult status = result(true, true, true, false,
                Collections.<String, String>emptyMap(), Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(2).startsWith("! "), lines.get(2));
        assertTrue(lines.get(2).contains("unknown"), lines.get(2));
        assertTrue(lines.get(2).contains("systemctl show failed"), lines.get(2));
    }

    @Test
    void failedServiceMarksProcessRow() {
        StatusResult status = result(true, true, true, true, show("failed", "4321"),
                Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(2).startsWith("! "), lines.get(2));
        assertTrue(lines.get(2).contains("failed"), lines.get(2));
    }

    @Test
    void linkProblemsMarkLinksRow() {
        StatusResult status = result(true, true, true, true, show("active", "4321"),
                Collections.singletonList("link /opt/demo/app.jar -> /srv/demo/app.jar"));

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(3).startsWith("! "), lines.get(3));
        assertTrue(lines.get(3).contains("problems"), lines.get(3));
        assertTrue(lines.get(3).contains("1 problem(s)"), lines.get(3));
    }

    @Test
    void inactiveProcessHasNoPidDetail() {
        StatusResult status = result(true, true, true, true, show("inactive", "0"),
                Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(lines.get(2).contains("inactive"), lines.get(2));
        assertFalse(lines.get(2).contains("pid"), lines.get(2));
    }

    @Test
    void bangPrefixesMatchDriftExactlyAcrossTheStatusMatrix() {
        List<StatusResult> cases = new ArrayList<StatusResult>();
        List<String> none = Collections.<String>emptyList();
        cases.add(full(true, true, true, true, true, true, show("active", "4321"), true, true, none));
        cases.add(full(true, false, true, true, true, true, show("active", "4321"), true, true, none));
        cases.add(full(false, false, true, true, true, true, show("active", "4321"), true, true, none));
        cases.add(full(true, true, true, true, true, false, show("active", "4321"), true, true, none));
        cases.add(full(true, true, true, false, true, false, show("active", "4321"), true, true, none));
        cases.add(full(true, true, false, true, true, false, show("active", "4321"), true, true, none));
        cases.add(full(true, true, false, false, true, true, show("active", "4321"), true, true, none));
        cases.add(full(true, true, true, true, false, false, show("active", "4321"), true, true, none));
        cases.add(full(true, true, true, true, true, true,
                show("active", "4321", "disabled"), true, true, none));
        cases.add(full(true, true, true, true, true, true, show("failed", "4321"), true, true, none));
        cases.add(full(true, true, true, true, true, true,
                Collections.<String, String>emptyMap(), false, true, none));
        cases.add(full(true, true, true, true, true, true, show("active", "4321"), true, true,
                Collections.singletonList("link /opt/demo/app.jar -> /srv/demo/app.jar")));
        Map<String, String> notFound = show("active", "4321");
        notFound.put("LoadState", "not-found");
        cases.add(full(true, true, true, true, true, true, notFound, true, true, none));

        for (StatusResult status : cases) {
            List<String> lines = StatusView.lines(report(status));
            boolean anyBang = false;
            for (String line : lines) {
                if (line.startsWith("! ")) {
                    anyBang = true;
                }
            }
            assertEquals(status.drift().isEmpty(), !anyBang,
                    "drift=" + status.drift() + " lines=" + lines);
        }
    }

    @Test
    void extraEnvFileMarksEnvRow() {
        StatusResult status = full(true, true, false, true, true, false,
                show("active", "4321"), true, true, Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(status.drift().get(0).contains("env file is extra"), status.drift().get(0));
        assertTrue(lines.get(1).startsWith("! "), lines.get(1));
    }

    @Test
    void autoStartMismatchMarksUnitRow() {
        StatusResult status = full(true, true, true, true, true, true,
                show("active", "4321", "disabled"), true, true, Collections.<String>emptyList());

        List<String> lines = StatusView.lines(report(status));

        assertTrue(status.drift().get(0).contains("auto-start disabled"), status.drift().get(0));
        assertTrue(lines.get(0).startsWith("! "), lines.get(0));
        assertTrue(lines.get(0).contains("auto-start disabled"), lines.get(0));
    }

    @Test
    void unknownRowsCarryTheFailureReason() {
        List<String> lines = StatusView.unknown("boom");

        assertEquals(4, lines.size());
        for (String line : lines) {
            assertTrue(line.contains("unknown"), line);
        }
        assertTrue(lines.get(2).contains("status query failed: boom"), lines.get(2));
    }
}
