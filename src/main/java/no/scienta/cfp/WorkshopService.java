package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Workshop registration: workflow/specs/workshop-registration.md (rules R1-R6, decisions D1-D8). */
@Service
public class WorkshopService {

    static final int SEATS = 20;

    private final TalkDao talkDao;
    private final RegistrationDao registrationDao;

    public WorkshopService(TalkDao talkDao, RegistrationDao registrationDao) {
        this.talkDao = talkDao;
        this.registrationDao = registrationDao;
    }

    /**
     * Locks the talk row first, so registrations for one workshop run one at a time: the duplicate and seat checks
     * can't race (R4, R5), and a decision or withdrawal on the same talk waits too. READ_COMMITTED, so the checks see
     * what the previous holder committed. The unique index on (talk_id, lower(email)) is the backstop.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> register(long workshopId, String name, String email) {
        try {
            if (!talkDao.lock(workshopId)) {
                throw notFound();
            }
            TalkDto talk = workshop(workshopId);
            if (!"ACCEPTED".equals(talk.status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + talk.status + " workshop takes no registrations");
            }
            if (registrationDao.emailRegistered(workshopId, email)) {
                throw duplicate();
            }
            if (registrationDao.count(workshopId) >= SEATS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "the workshop is full (" + SEATS + " seats)");
            }
            return registrationDao.insert(workshopId, name, email);
        } catch (DuplicateKeyException e) {
            throw duplicate();
        } catch (DataAccessException e) {
            if (LockTimeoutException.isLockNotAvailable(e)) {
                throw new LockTimeoutException(e);
            }
            throw e;
        }
    }

    public List<Map<String, Object>> registrations(long workshopId) {
        workshop(workshopId);
        return registrationDao.list(workshopId);
    }

    /** The talk, if it's a workshop; otherwise 404 (D2). */
    private TalkDto workshop(long id) {
        TalkDto talk = talkDao.findById(id);
        if (talk == null || !"WORKSHOP".equals(talk.format)) {
            throw notFound();
        }
        return talk;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "workshop not found");
    }

    private static ResponseStatusException duplicate() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered for the workshop");
    }
}
