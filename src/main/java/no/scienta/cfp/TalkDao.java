package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class TalkDao {

    // Each talk with its rating count and total, so a list is one query rather than one more per talk. LATERAL, so the
    // totals are looked up per selected talk through rating's primary key, not aggregated over every rating.
    private static final String SELECT = "SELECT t.id, t.title, t.format, t.duration, t.level, t.status, t.speaker_id, "
            + "t.submitted_at, r.cnt AS rating_count, r.total AS rating_total FROM talk t "
            + "CROSS JOIN LATERAL (SELECT COUNT(*) AS cnt, COALESCE(SUM(score), 0) AS total FROM rating "
            + "WHERE rating.talk_id = t.id) r";

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
        t.ratingCount = rs.getInt("rating_count");
        t.averageRating = average(rs.getLong("rating_total"), t.ratingCount);
        return t;
    };

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query(SELECT + " WHERE t.id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query(SELECT + " WHERE t.speaker_id = ? ORDER BY t.id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query(SELECT + " ORDER BY t.id", mapper);
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

    /** The co-speakers of every given talk, in the order they were added, in one query. Talks without any are absent. */
    public Map<Long, List<Long>> coSpeakerIds(List<Long> talkIds) {
        Map<Long, List<Long>> byTalk = new HashMap<>();
        if (talkIds.isEmpty()) {
            return byTalk;
        }
        jdbc.query("SELECT talk_id, speaker_id FROM talk_co_speaker WHERE talk_id = ANY (?) ORDER BY added_order",
                rs -> {
                    byTalk.computeIfAbsent(rs.getLong("talk_id"), k -> new ArrayList<>()).add(rs.getLong("speaker_id"));
                }, (Object) talkIds.toArray(new Long[0]));
        return byTalk;
    }

    // 1 decimal, half up (4.25 -> 4.3); null when nobody has rated the talk
    static Double average(long total, int count) {
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
    }

    // Call only while holding the talk row lock in a transaction. ON CONFLICT keeps a duplicate a no-op at the
    // database level too: no error, and the existing row (and its added_order) is left untouched.
    public void addCoSpeaker(long talkId, long speakerId) {
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?) "
                + "ON CONFLICT (talk_id, speaker_id) DO NOTHING", talkId, speakerId);
    }

    /** @return whether the speaker was a co-speaker of the talk (and now isn't). */
    public boolean removeCoSpeaker(long talkId, long speakerId) {
        return 1 == jdbc.update("DELETE FROM talk_co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
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

    // A speaker's load: the talks in the running (not REJECTED or WITHDRAWN) they present as main speaker or co-speaker.
    // Computed, not stored, so existing rows count from the first deploy (workflow/specs/submission-cap-cr.md D1, D7).
    static final List<String> IN_THE_RUNNING = List.of("SUBMITTED", "WAITLISTED", "ACCEPTED");
    private static final String LOAD = "(SELECT COUNT(*) FROM talk WHERE status IN ('" + String.join("', '", IN_THE_RUNNING) + "') "
            + "AND id IN (SELECT id FROM talk WHERE speaker_id = ? UNION SELECT talk_id FROM talk_co_speaker WHERE speaker_id = ?))";

    /**
     * Locks the speaker row for the rest of the current transaction: every change to a speaker's load (a submission, an
     * added co-speakership) takes it before counting. Waits as long as the transaction's lock_timeout allows.
     */
    public boolean lockSpeaker(long speakerId) {
        return !jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", Long.class, speakerId).isEmpty();
    }

    /** The speaker's load. Call while holding the speaker lock, under READ COMMITTED, to count what committed before it. */
    public int load(long speakerId) {
        Integer n = jdbc.queryForObject("SELECT " + LOAD, Integer.class, speakerId, speakerId);
        return n == null ? 0 : n;
    }

    /**
     * Inserts the talk only if the speaker's load is below {@code max}. Returns the new id, or null.
     * The speaker row is locked first so two submissions for the same speaker can't both see room under the limit.
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        lockSpeaker(speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE " + LOAD + " < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}
