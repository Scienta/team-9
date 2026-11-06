package no.scienta.cfp;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ProgrammeDao {

    private final JdbcTemplate jdbc;

    public ProgrammeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the slot for the rest of the current transaction, waiting at most 2 s (then 55P03), as TalkDao.lock does.
     * Every PUT into the slot takes this lock first, so the checks within a slot can't race.
     */
    public void lockSlot(String slot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ProgrammeDao.lockSlot needs a transaction");
        }
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.queryForList("SELECT slot FROM programme_slot WHERE slot = ? FOR UPDATE", String.class, slot);
    }

    /** The talk in this room and slot, or null. */
    public Long talkAt(String room, String slot) {
        List<Long> ids = jdbc.queryForList("SELECT talk_id FROM programme WHERE room = ? AND slot = ?", Long.class, room, slot);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public boolean scheduled(long talkId) {
        return jdbc.queryForObject("SELECT count(*) FROM programme WHERE talk_id = ?", Integer.class, talkId) > 0;
    }

    /** Every main speaker and co-speaker of the talks in this slot, in any room. */
    public List<Long> speakersIn(String slot) {
        return jdbc.queryForList("SELECT t.speaker_id FROM programme p JOIN talk t ON t.id = p.talk_id WHERE p.slot = ? "
                + "UNION SELECT c.speaker_id FROM programme p JOIN talk_co_speaker c ON c.talk_id = p.talk_id WHERE p.slot = ?",
                Long.class, slot, slot);
    }

    public void insert(String room, String slot, long talkId) {
        jdbc.update("INSERT INTO programme (room, slot, talk_id) VALUES (?, ?, ?)", room, slot, talkId);
    }

    /** Returns false if the place was empty. */
    public boolean delete(String room, String slot) {
        return jdbc.update("DELETE FROM programme WHERE room = ? AND slot = ?", room, slot) > 0;
    }
}
