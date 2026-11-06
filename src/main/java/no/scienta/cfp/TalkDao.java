package no.scienta.cfp;

import java.util.Date;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class TalkDao {

    private static final String COLUMNS = "id, title, format, duration, level, status, speaker_id, submitted_at";

    private final JdbcTemplate jdbc;

    private final RowMapper<TalkDto> mapper = (rs, rowNum) -> {
        TalkDto t = new TalkDto();
        t.id = rs.getLong("id");
        t.title = rs.getString("title");
        t.format = rs.getString("format");
        t.durationMinutes = rs.getInt("duration");
        t.level = rs.getString("level");
        t.status = rs.getString("status");
        t.speakerId = rs.getLong("speaker_id");
        long millis = rs.getLong("submitted_at");
        t.submittedAt = new Date(millis);
        t.submittedOn = Utils.toUtcDate(millis);
        return t;
    };

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE speaker_id = ? ORDER BY id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk ORDER BY id", mapper);
    }

    /**
     * Locks the talk row for the rest of the current transaction, waiting at most 2 s (then Postgres raises
     * 55P03 lock_not_available). SET LOCAL, not SET: a session setting would leak to the next pool user.
     */
    public boolean lock(long id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TalkDao.lock needs a transaction");
        }
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        return !jdbc.queryForList("SELECT id FROM talk WHERE id = ? FOR UPDATE", Long.class, id).isEmpty();
    }

    public List<Long> coSpeakerIds(long talkId) {
        return jdbc.queryForList("SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ? ORDER BY added_order",
                Long.class, talkId);
    }

    // Call only while holding the talk row lock in a transaction. ON CONFLICT keeps a duplicate a no-op at the
    // database level too: no error, and the existing row (and its added_order) is left untouched.
    public void addCoSpeaker(long talkId, long speakerId) {
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?) "
                + "ON CONFLICT (talk_id, speaker_id) DO NOTHING", talkId, speakerId);
    }

    public void removeCoSpeaker(long talkId, long speakerId) {
        jdbc.update("DELETE FROM talk_co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
    }

    public int reviewerCount(long talkId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(DISTINCT reviewer_id) FROM rating WHERE talk_id = ?", Integer.class, talkId);
        return n == null ? 0 : n;
    }

    public void updateStatus(long talkId, String status) {
        jdbc.update("UPDATE talk SET status = ? WHERE id = ?", status, talkId);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM talk WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * Inserts the talk only if the speaker has fewer than {@code max} talks. Returns the new id, or null.
     * The speaker row is locked first so two submissions for the same speaker can't both see room under the limit.
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (SELECT COUNT(*) FROM talk WHERE speaker_id = ?) < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}
