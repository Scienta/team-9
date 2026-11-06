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

    // Each talk with its rating count, total, highest and lowest score, so a list is one query rather than one more per
    // talk. LATERAL, so they're looked up per selected talk through rating's primary key, not aggregated over every
    // rating. All four come from one statement, so they agree with each other (ratings-cr).
    private static final String SELECT = "SELECT t.id, t.title, t.format, t.duration, t.level, t.status, t.speaker_id, "
            + "t.submitted_at, r.cnt AS rating_count, r.total AS rating_total, r.high AS rating_high, r.low AS rating_low "
            + "FROM talk t CROSS JOIN LATERAL (SELECT COUNT(*) AS cnt, COALESCE(SUM(score), 0) AS total, "
            + "COALESCE(MAX(score), 0) AS high, COALESCE(MIN(score), 0) AS low FROM rating WHERE rating.talk_id = t.id) r";

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
        t.averageRating = average(rs.getLong("rating_total"), t.ratingCount, rs.getInt("rating_high"), rs.getInt("rating_low"));
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

    /** Talks with at least this many ratings leave one highest and one lowest score out of the average (ratings-cr R2). */
    static final int TRIM_FROM = 5;

    /**
     * The average of the scores, leaving out exactly one highest and one lowest from {@link #TRIM_FROM} ratings on (R2);
     * 2 decimals, ties to the even neighbour (R3: 3.125 -> 3.12), rounded on the exact fraction (D4). Null when nobody
     * has rated the talk (R6).
     */
    static Double average(long total, int count, int high, int low) {
        if (count == 0) {
            return null;
        }
        if (count >= TRIM_FROM) {
            total -= high + low;
            count -= 2;
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN).doubleValue();
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
