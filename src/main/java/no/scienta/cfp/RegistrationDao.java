package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationDao {

    private static final RowMapper<Map<String, Object>> REGISTRATION = (rs, i) -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("workshopId", rs.getLong("talk_id"));
        r.put("name", rs.getString("name"));
        r.put("email", rs.getString("email"));
        return r;
    };

    private final JdbcTemplate jdbc;

    public RegistrationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count(long talkId) {
        return jdbc.queryForObject("SELECT count(*) FROM workshop_registration WHERE talk_id = ?", Integer.class, talkId);
    }

    public boolean emailRegistered(long talkId, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM workshop_registration WHERE talk_id = ? AND lower(email) = lower(?)",
                Integer.class, talkId, email) > 0;
    }

    public Map<String, Object> insert(long talkId, String name, String email) {
        return jdbc.queryForObject("INSERT INTO workshop_registration (talk_id, name, email, registered_at) "
                + "VALUES (?, ?, ?, ?) RETURNING id, talk_id, name, email", REGISTRATION,
                talkId, name, email, System.currentTimeMillis());
    }

    public List<Map<String, Object>> list(long talkId) {
        return jdbc.query("SELECT id, talk_id, name, email FROM workshop_registration WHERE talk_id = ? ORDER BY id",
                REGISTRATION, talkId);
    }
}
