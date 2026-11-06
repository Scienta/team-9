package no.scienta.cfp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Scheduling: workflow/specs/scheduling.md (rules R1-R9, decisions D1-D11). */
@Service
public class ProgrammeService {

    private final TalkDao talkDao;
    private final ProgrammeDao programmeDao;

    public ProgrammeService(TalkDao talkDao, ProgrammeDao programmeDao) {
        this.talkDao = talkDao;
        this.programmeDao = programmeDao;
    }

    /**
     * Checks in the order of D9. The slot is locked before any check that reads the programme, so PUTs into one slot run
     * one at a time and R5 and R7 can't race; READ_COMMITTED, so each sees what the previous holder committed. R6 spans
     * slots: the unique index on talk_id holds it, and a violation answers 409.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> schedule(String room, String slot, Map<String, Object> body) {
        Programme.checkPlace(room, slot);
        long talkId = talkId(body);
        try {
            TalkDto talk = talkDao.findById(talkId);
            if (talk == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
            }
            programmeDao.lockSlot(slot);
            Long current = programmeDao.talkAt(room, slot);
            if (current != null && current == talkId) {
                // D6: already there, nothing changes, even if the talk has changed since (R8)
                return Programme.entry(room, slot, talkId);
            }
            if (!"ACCEPTED".equals(talk.status)) {
                throw conflict("a " + talk.status + " talk can't go on the programme");
            }
            if ("WORKSHOP".equals(talk.format) && !Programme.LONG_SLOT.equals(slot)) {
                throw conflict("a workshop needs the long slot " + Programme.LONG_SLOT);
            }
            if (current != null) {
                throw conflict(room + " " + slot + " already has a talk");
            }
            if (programmeDao.scheduled(talkId)) {
                throw conflict("the talk is already on the programme; take it out first");
            }
            List<Long> speakers = new ArrayList<>(talkDao.coSpeakerIds(talkId));
            speakers.add(talk.speakerId);
            speakers.retainAll(programmeDao.speakersIn(slot));
            if (!speakers.isEmpty()) {
                throw conflict("speaker " + speakers.get(0) + " already speaks in " + slot);
            }
            programmeDao.insert(room, slot, talkId);
            return Programme.entry(room, slot, talkId);
        } catch (DuplicateKeyException e) {
            throw conflict("the talk is already on the programme; take it out first");
        } catch (DataAccessException e) {
            if (LockTimeoutException.isLockNotAvailable(e)) {
                throw new LockTimeoutException("slot " + slot + " is busy, retry shortly", e);
            }
            throw e;
        }
    }

    public Map<String, Object> entry(String room, String slot) {
        Programme.checkPlace(room, slot);
        Long talkId = programmeDao.talkAt(room, slot);
        if (talkId == null) {
            throw empty(room, slot);
        }
        return Programme.entry(room, slot, talkId);
    }

    public void unschedule(String room, String slot) {
        Programme.checkPlace(room, slot);
        if (!programmeDao.delete(room, slot)) {
            throw empty(room, slot);
        }
    }

    /** A JSON whole number >= 1 (D2). Jackson gives Integer or Long for those; 5.0, "5", true and huge numbers aren't. */
    private static long talkId(Map<String, Object> body) {
        Object value = body.get("talkId");
        if (!(value instanceof Integer) && !(value instanceof Long) || ((Number) value).longValue() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId must be a whole number from 1");
        }
        return ((Number) value).longValue();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException empty(String room, String slot) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing in " + room + " " + slot);
    }
}
