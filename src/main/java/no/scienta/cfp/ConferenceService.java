package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Speakers, talks and ratings.
 *
 * TODO split this up (CFP-140)
 */
@Service
public class ConferenceService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceService.class);

    private static final Map<String, Integer> DURATIONS = new HashMap<>();

    static {
        DURATIONS.put("LIGHTNING_10", 10);
        DURATIONS.put("LIGHTNING_20", 20);
        DURATIONS.put("PRESENTATION", 45);
        DURATIONS.put("WORKSHOP", 120);
        // DURATIONS.put("KEYNOTE", 60);  // keynotes are invited, not submitted (2021)
    }

    private final SpeakerDao speakerDao;
    private final TalkDao talkDao;
    private final RatingDao ratingDao;
    private final NotificationService notifications;

    public ConferenceService(SpeakerDao speakerDao, TalkDao talkDao, RatingDao ratingDao,
                             NotificationService notifications) {
        this.speakerDao = speakerDao;
        this.talkDao = talkDao;
        this.ratingDao = ratingDao;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- speakers

    @Transactional
    public Map<String, Object> registerSpeaker(SpeakerForm form) {
        if (speakerDao.emailTaken(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        long id;
        try {
            id = speakerDao.insert(form.getName(), form.getEmail(), form.getBio());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        log.info("registered speaker " + id + " (" + form.getEmail() + ")");
        notifications.speakerRegistered(form.getEmail(), form.getName());
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("id", id);
        speaker.put("name", form.getName());
        speaker.put("email", form.getEmail());
        speaker.put("bio", form.getBio());
        return speaker;
    }

    public Map<String, Object> getSpeaker(long id) {
        Map<String, Object> speaker = speakerDao.findById(id);
        if (speaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        return speaker;
    }

    @Transactional
    public Map<String, Object> updateSpeaker(long id, String name, String email, String bio) {
        Map<String, Object> existing = getSpeaker(id);
        if (Utils.isEmpty(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (bio != null && bio.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bio too long");
        }
        if (!email.equalsIgnoreCase((String) existing.get("email")) && speakerDao.emailTaken(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        try {
            speakerDao.update(id, name, email, bio);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        return getSpeaker(id);
    }

    public List<Map<String, Object>> searchSpeakers(String q) {
        return speakerDao.search(q);
    }

    // ---------------------------------------------------------------- talks

    @Transactional
    public TalkDto submitTalk(TalkForm form) {
        if (form.title == null || form.title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        if (form.format == null || !DURATIONS.containsKey(form.format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be one of " + DURATIONS.keySet());
        }
        if (form.level == null || !Arrays.asList("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(form.level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown level");
        }
        if (form.speakerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId is required");
        }
        if (!speakerDao.exists(form.speakerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speaker " + form.speakerId + " does not exist");
        }

        int duration;
        switch (form.format) {
            case "LIGHTNING_10":
                duration = 10;
                break;
            case "LIGHTNING_20":
                duration = 20;
                break;
            case "WORKSHOP":
                duration = 120;
                break;
            default:
                duration = 45;
        }

        Long id = checkSubmissionLimit(form, duration);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a speaker can submit at most 3 talks");
        }
        Map<String, Object> speaker = speakerDao.findById(form.speakerId);
        notifications.talkSubmitted((String) speaker.get("email"), form.title);
        return getTalk(id);
    }

    /**
     * Checks that the speaker is below the limit of 3 talks.
     */
    private Long checkSubmissionLimit(TalkForm form, int duration) {
        return talkDao.insertIfBelow(3, form.title, form.format, duration, form.level, form.speakerId,
                System.currentTimeMillis());
    }

    // Co-speaker mutations lock the talk row, then read membership. That only sees the latest committed
    // members under READ COMMITTED (a REPEATABLE READ snapshot would predate the lock wait), so the level is
    // pinned here instead of inherited from the database default. See workflow/co-speakers.md.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TalkDto addCoSpeaker(long talkId, long speakerId) {
        try {
            lockTalk(talkId);
            getSpeaker(speakerId);
            TalkDto talk = getTalk(talkId);
            if (talk.speakerId == speakerId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "main speaker cannot be a co-speaker");
            }
            if (!talk.coSpeakerIds.contains(speakerId)) {
                if (talk.coSpeakerIds.size() >= 2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk can have at most 2 co-speakers");
                }
                talkDao.addCoSpeaker(talkId, speakerId);
            }
            return getTalk(talkId);
        } catch (DataAccessException e) {
            throw lockTimeoutOr(e);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TalkDto removeCoSpeaker(long talkId, long speakerId) {
        try {
            lockTalk(talkId);
            getSpeaker(speakerId);
            if (!talkDao.removeCoSpeaker(talkId, speakerId)) {
                // Includes the main speaker, and a repeated DELETE: unlike a repeated PUT, it's not a no-op.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker is not a co-speaker of this talk");
            }
            return getTalk(talkId);
        } catch (DataAccessException e) {
            throw lockTimeoutOr(e);
        }
    }

    /** Only lock_not_available (55P03) becomes 503; deadlocks, serialization failures etc. propagate unchanged. */
    private static RuntimeException lockTimeoutOr(DataAccessException e) {
        if (LockTimeoutException.isLockNotAvailable(e)) {
            log.warn("talk mutation timed out waiting for talk lock: " + e.getMostSpecificCause().getMessage());
            return new LockTimeoutException(e);
        }
        return e;
    }

    private void lockTalk(long talkId) {
        if (!talkDao.lock(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
    }

    public TalkDto getTalk(long id) {
        TalkDto talk = talkDao.findById(id);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        addRatings(talk);
        return talk;
    }

    public List<TalkDto> getTalksForSpeaker(long speakerId) {
        if (speakerDao.findById(speakerId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        List<TalkDto> talks = talkDao.findBySpeaker(speakerId);
        for (TalkDto t : talks) {
            addRatings(t);
        }
        return talks;
    }

    public List<TalkDto> listTalks(String sort, Long speakerId) {
        if (sort != null && !sort.equals("rating")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be 'rating'");
        }
        List<TalkDto> talks = speakerId == null ? talkDao.findAll() : talkDao.findBySpeaker(speakerId);
        for (TalkDto t : talks) {
            addRatings(t);
        }
        if ("rating".equals(sort)) {
            List<TalkDto> rated = new ArrayList<>();
            List<TalkDto> unrated = new ArrayList<>();
            for (TalkDto t : talks) {
                if (t.ratingCount > 0) {
                    rated.add(t);
                } else {
                    unrated.add(t);
                }
            }
            rated.sort((a, b) -> {
                int c = Double.compare(b.averageRating, a.averageRating);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(b.ratingCount, a.ratingCount);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.id, b.id);
            });
            rated.addAll(unrated);
            return rated;
        }
        return talks;
    }

    private void addRatings(TalkDto talk) {
        talk.coSpeakerIds = talkDao.coSpeakerIds(talk.id);
        Map<String, Object> stats = ratingDao.stats(talk.id);
        int count = ((Number) stats.get("cnt")).intValue();
        long total = ((Number) stats.get("total")).longValue();
        talk.ratingCount = count;
        talk.averageRating = count == 0 ? null : calculateAverage(total, count);
    }

    // 1 decimal, half up (4.25 -> 4.3)
    private Double calculateAverage(long total, int count) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
        // return Utils.roundRating((double) total / count);
    }

    /**
     * Committee decision: SUBMITTED becomes ACCEPTED, REJECTED or WAITLISTED, and WAITLISTED becomes ACCEPTED or
     * REJECTED. Everything else is final. Accepting needs ratings from two different reviewers. The talk row is
     * locked, so two simultaneous decisions are applied one after the other, the second against the first's result.
     * READ_COMMITTED, so the status is read after the lock wait and sees what the previous decision committed.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TalkDto decide(long talkId, Map<String, Object> body) {
        Object requested = body.get("status");
        if (!(requested instanceof String) || !Arrays.asList("ACCEPTED", "REJECTED", "WAITLISTED").contains(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACCEPTED, REJECTED or WAITLISTED");
        }
        String status = (String) requested;
        try {
            lockTalk(talkId);
            String current = getTalk(talkId).status;
            boolean open = "SUBMITTED".equals(current) || "WAITLISTED".equals(current);
            if (!open || status.equals(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + current + " talk can't become " + status);
            }
            if ("ACCEPTED".equals(status) && talkDao.reviewerCount(talkId) < 2) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk needs ratings from 2 reviewers to be accepted");
            }
            talkDao.updateStatus(talkId, status);
            return getTalk(talkId);
        } catch (DataAccessException e) {
            throw lockTimeoutOr(e);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TalkDto withdraw(long talkId, Map<String, Object> body) {
        Object speakerId = body.get("speakerId");
        if (!(speakerId instanceof Number) || ((Number) speakerId).longValue() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId is required");
        }
        try {
            lockTalk(talkId);
            TalkDto talk = getTalk(talkId);
            if (talk.speakerId != ((Number) speakerId).longValue()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the speaker can withdraw a talk");
            }
            if ("REJECTED".equals(talk.status) || "WITHDRAWN".equals(talk.status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + talk.status + " talk can't be withdrawn");
            }
            talkDao.updateStatus(talkId, "WITHDRAWN");
            return getTalk(talkId);
        } catch (DataAccessException e) {
            throw lockTimeoutOr(e);
        }
    }

    // ---------------------------------------------------------------- statistics

    public Map<String, Object> statistics() {
        List<TalkDto> talks = listTalks(null, null);
        Map<String, Integer> byFormat = new LinkedHashMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<Long> speakersWithTalks = new ArrayList<>();
        int minutes = 0;
        double ratingSum = 0;
        int rated = 0;
        for (TalkDto t : talks) {
            byFormat.put(t.format, byFormat.getOrDefault(t.format, 0) + 1);
            byLevel.put(t.level, byLevel.getOrDefault(t.level, 0) + 1);
            byStatus.put(t.status, byStatus.getOrDefault(t.status, 0) + 1);
            if (!speakersWithTalks.contains(t.speakerId) && speakerDao.findById(t.speakerId) != null) {
                speakersWithTalks.add(t.speakerId);
            }
            minutes += t.durationMinutes;
            if (t.averageRating != null) {
                ratingSum += t.averageRating;
                rated++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("speakers", speakerDao.count());
        stats.put("speakersWithTalks", speakersWithTalks.size());
        stats.put("talks", talks.size());
        stats.put("byFormat", byFormat);
        stats.put("byLevel", byLevel);
        stats.put("byStatus", byStatus);
        stats.put("totalHours", minutes / 60.0);
        stats.put("averageRating", rated == 0 ? null : Utils.roundRating(ratingSum / rated));
        stats.put("generated", Utils.now());
        return stats;
    }

    // ---------------------------------------------------------------- ratings

    @Transactional
    public Map<String, Object> rate(long talkId, long reviewerId, Map<String, Object> body) {
        if (reviewerId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewerId must be positive");
        }
        Object score = body.get("score");
        if (!(score instanceof Integer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be a whole number");
        }
        int s = (Integer) score;
        if (s < 1 || s > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be 1-5");
        }
        if (!talkDao.exists(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        String now = Utils.now();
        ratingDao.save(talkId, reviewerId, s, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("talkId", talkId);
        result.put("reviewerId", reviewerId);
        result.put("score", s);
        result.put("ratedAtDisplay", Utils.usShort(Utils.parse(now)));
        return result;
    }
}
