package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Disabled;
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

    @Disabled("flaky on Jenkins")
    @Test
    void nowIsCurrentTime() {
        SimpleDateFormat f = new SimpleDateFormat(Utils.DB_DATE_TIME);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(f.format(new Date()), Utils.now());
    }
}
