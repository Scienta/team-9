package no.scienta.cfp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads for the programme export (workflow/specs/programme-export.md): two queries whatever the programme's size. */
@Repository
public class ProgrammeExportDao {

    private final JdbcTemplate jdbc;

    public ProgrammeExportDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every scheduled talk with its current title, format and main speaker's name, in programme order (R2). */
    public List<Map<String, Object>> rows() {
        // slot ids S1..S5 sort as their start times, room ids R1..R3 as the request lists them
        return jdbc.queryForList("SELECT p.slot, p.room, t.id AS talk_id, t.title, t.format, s.name AS speaker "
                + "FROM programme p JOIN talk t ON t.id = p.talk_id JOIN speaker s ON s.id = t.speaker_id "
                + "ORDER BY p.slot, p.room");
    }

    /** Talk id -> co-speakers' current names, in the order they were added (R4). */
    public Map<Long, List<String>> coSpeakerNames() {
        Map<Long, List<String>> names = new HashMap<>();
        jdbc.query("SELECT c.talk_id, s.name FROM programme p JOIN talk_co_speaker c ON c.talk_id = p.talk_id "
                + "JOIN speaker s ON s.id = c.speaker_id ORDER BY c.talk_id, c.added_order", rs -> {
                    names.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return names;
    }
}
