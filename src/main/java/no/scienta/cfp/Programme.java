package no.scienta.cfp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The fixed venue (scheduling R1): three rooms, and the same five slots in every room. */
final class Programme {

    static final List<String> ROOMS = Arrays.asList("R1", "R2", "R3");

    /** Slot -> {start, end}, as on the signs. */
    static final Map<String, String[]> SLOTS = new LinkedHashMap<>();

    /** The only slot long enough for a workshop (R4). */
    static final String LONG_SLOT = "S4";

    static {
        SLOTS.put("S1", new String[] {"09:00", "10:00"});
        SLOTS.put("S2", new String[] {"10:15", "11:15"});
        SLOTS.put("S3", new String[] {"11:30", "12:30"});
        SLOTS.put("S4", new String[] {"13:30", "15:30"});
        SLOTS.put("S5", new String[] {"15:45", "16:45"});
    }

    private Programme() {
    }

    /** 404 unless room and slot are listed values, case-sensitive: any other place doesn't exist (D1). */
    static void checkPlace(String room, String slot) {
        if (!ROOMS.contains(room)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no room " + room + "; rooms are " + ROOMS);
        }
        if (!SLOTS.containsKey(slot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no slot " + slot + "; slots are " + SLOTS.keySet());
        }
    }

    static Map<String, Object> entry(String room, String slot, long talkId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("room", room);
        entry.put("slot", slot);
        entry.put("start", SLOTS.get(slot)[0]);
        entry.put("end", SLOTS.get(slot)[1]);
        entry.put("talkId", talkId);
        return entry;
    }
}
