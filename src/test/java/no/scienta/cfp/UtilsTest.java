package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void isEmpty() {
        assertTrue(Utils.isEmpty(null));
        assertTrue(Utils.isEmpty(""));
        assertTrue(Utils.isEmpty("   "));
        assertFalse(Utils.isEmpty("a"));
    }

    @Test
    void isNumeric() {
        assertTrue(Utils.isNumeric("123"));
        assertFalse(Utils.isNumeric("12a"));
        assertFalse(Utils.isNumeric(""));
    }

    @Test
    void capitalize() {
        assertEquals("Workshop", Utils.capitalize("WORKSHOP"));
    }

    // Averages are shown with one decimal, halves rounded up (ratings spec)
    @Test
    void roundsRatingToOneDecimal() {
        assertEquals(4.3, Utils.roundRating(4.25));
        assertEquals(3.7, Utils.roundRating(3.6666));
        assertEquals(4.5, Utils.roundRating(4.5));
    }

    @Test
    void usShortFormat() {
        assertEquals("11/4/26, 1:05 PM", Utils.usShort(new Date(1793797500000L)));
        assertEquals("11/4/26, 12:00 AM", Utils.usShort(new Date(1793750400000L)));
    }

    @Test
    void submittedOnIsUtcDate() {
        assertEquals("2026-11-04", Utils.toUtcDate(1793836799000L).toString());
    }

    @Test
    void csvEscapesQuotesAndNewlines() {
        assertEquals("\"a \"\"b\"\"\"", Utils.csv("a \"b\""));
        assertEquals("\"a\nb\"", Utils.csv("a\nb"));
        assertEquals("plain", Utils.csv("plain"));
    }

    // Was @Disabled as flaky: it compared to the second. Now it allows a few seconds, and runs with a default time
    // zone far from UTC so a missing setTimeZone(UTC) fails on UTC machines (CI) too.
    @Test
    void nowIsCurrentUtcTime() throws Exception {
        withDefaultZone("Pacific/Kiritimati", () -> {
            long before = System.currentTimeMillis();
            String now = Utils.now();
            long after = System.currentTimeMillis();
            SimpleDateFormat f = new SimpleDateFormat(Utils.DB_DATE_TIME);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long parsed = f.parse(now).getTime();
            assertTrue(parsed >= before - 1000 && parsed <= after, now);
        });
    }

    @Test
    void parseReadsUtc() throws Exception {
        withDefaultZone("Pacific/Kiritimati", () ->
                assertEquals(1793797500000L, Utils.parse("2026-11-04 13:05:00").getTime()));
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(RuntimeException.class, () -> Utils.parse("yesterday"));
    }

    @Test
    void todayIsTheLocalDateInTheOldAdminFormat() {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy");
        String before = f.format(new Date());
        String today = Utils.today();
        String after = f.format(new Date());
        assertTrue(today.equals(before) || today.equals(after), today);
        assertTrue(today.matches("\\d{2}\\.\\d{2}\\.\\d{4}"), today);
    }

    // 2026-11-04 23:30 UTC is already 5 November in Oslo.
    @Test
    void norwegianDateIsTheOsloDate() throws Exception {
        withDefaultZone("UTC", () -> assertEquals("5. nov. 2026", Utils.norwegianDate(new Date(1793835000000L))));
    }

    @Test
    void validEmailNeedsAnAtWithADotAfterIt() {
        assertTrue(Utils.isValidEmail("ada@example.com"));
        assertTrue(Utils.isValidEmail("a.b@c.d"));
        assertTrue(Utils.isValidEmail("@b.c"));
        assertFalse(Utils.isValidEmail(null));
        assertFalse(Utils.isValidEmail(""));
        assertFalse(Utils.isValidEmail("example.com"));
        assertFalse(Utils.isValidEmail("ada@example"));
        assertFalse(Utils.isValidEmail("a.b@example"));
    }

    @Test
    void capitalizeLeavesEmptyValuesAlone() {
        assertNull(Utils.capitalize(null));
        assertEquals("", Utils.capitalize(""));
        assertEquals("Lightning 10", Utils.capitalize("LIGHTNING 10"));
    }

    @Test
    void csvLeavesNullEmptyAndQuotesSemicolonsAndCarriageReturns() {
        assertEquals("", Utils.csv(null));
        assertEquals("\"a;b\"", Utils.csv("a;b"));
        assertEquals("\"a\rb\"", Utils.csv("a\rb"));
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void withDefaultZone(String zone, Body body) throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            body.run();
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
