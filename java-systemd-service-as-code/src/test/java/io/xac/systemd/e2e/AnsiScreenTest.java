package io.xac.systemd.e2e;

import io.xac.systemd.tui.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiScreenTest {

    @Test
    void replaysCursorForwardAsSpaces() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[Hab\u001b[3Ccd", 3, 10);

        assertEquals("ab   cd", screen.line(0).trim());
        assertEquals('c', screen.charAt(5, 0));
    }

    @Test
    void mapsSgrColorsToRoles() {
        String raw = "\u001b[H"
                + "\u001b[36mcyan\u001b[0m"
                + "\u001b[1C"
                + "\u001b[31mred\u001b[39m"
                + "\u001b[1C"
                + "\u001b[32mgreen\u001b[0m"
                + "\u001b[1C"
                + "\u001b[90mdim\u001b[0m";

        AnsiScreen.Screen screen = AnsiScreen.parse(raw, 3, 40);

        assertTrue(screen.hasRole(Role.ACCENT));
        assertTrue(screen.hasRole(Role.WARN));
        assertTrue(screen.hasRole(Role.OK));
        assertTrue(screen.hasRole(Role.DIM));
        assertEquals(Role.ACCENT, screen.roleAt(0, 0));
        assertEquals(Role.WARN, screen.roleAt(5, 0));
    }

    @Test
    void mapsWhiteOnBlueToSelected() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[H\u001b[37;44m> row\u001b[0m", 2, 20);

        assertEquals(Role.SELECTED, screen.roleAt(0, 0));
        assertEquals("> row", screen.line(0).trim());
    }

    @Test
    void keepsSecondColumnOfWideGlyphBlank() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[H中文", 2, 10);

        assertEquals('中', screen.charAt(0, 0));
        assertEquals(' ', screen.charAt(1, 0));
        assertEquals('文', screen.charAt(2, 0));
    }

    @Test
    void clearsScreenOnEd2() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[Hgone\u001b[2J", 2, 10);

        assertEquals("", screen.line(0).trim());
    }

    @Test
    void handlesCarriageReturnAndNewline() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[Hone\r\ntwo", 3, 10);

        assertEquals("one", screen.line(0).trim());
        assertEquals("two", screen.line(1).trim());
    }

    @Test
    void reportsRolesPerRow() {
        AnsiScreen.Screen screen = AnsiScreen.parse("\u001b[H\u001b[36m!\u001b[0m plain", 2, 20);

        assertEquals(Role.ACCENT, screen.rolesInRow(0).get(0));
        assertEquals(1, screen.rolesInRow(0).size());
    }
}
