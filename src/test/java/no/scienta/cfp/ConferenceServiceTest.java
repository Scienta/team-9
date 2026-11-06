package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConferenceServiceTest {

    private SpeakerDao speakerDao;
    private TalkDao talkDao;
    private RatingDao ratingDao;
    private NotificationService notifications;
    private ConferenceService service;

    @BeforeEach
    void setUp() {
        speakerDao = mock(SpeakerDao.class);
        talkDao = mock(TalkDao.class);
        ratingDao = mock(RatingDao.class);
        notifications = mock(NotificationService.class);
        service = new ConferenceService(speakerDao, talkDao, ratingDao, notifications);
        when(ratingDao.stats(anyLong())).thenReturn(stats(0, 0));
        when(speakerDao.findById(anyLong())).thenReturn(null); // Mockito's default for a Map is an empty one
    }

    // ---------------------------------------------------------------- talks

    @Test
    void talkWithoutTitleIsRejected() {
        TalkForm form = form();
        form.title = " ";
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.submitTalk(form));
    }

    @Test
    void unknownFormatIsRejected() {
        TalkForm form = form();
        form.format = "KEYNOTE";
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.submitTalk(form));
    }

    @Test
    void unknownSpeakerIsRejected() {
        when(speakerDao.exists(7L)).thenReturn(false);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.submitTalk(form()));
    }

    @Test
    void submittedTalkNotifiesTheSpeaker() {
        when(speakerDao.exists(7L)).thenReturn(true);
        when(speakerDao.findById(7L)).thenReturn(speaker(7, "Ada", "ada@example.com"));
        when(talkDao.insertIfBelow(eq(3), eq("Legacy is a feeling"), eq("PRESENTATION"), eq(45), eq("INTERMEDIATE"),
                eq(7L), anyLong())).thenReturn(11L);
        when(talkDao.findById(11L)).thenReturn(talk(11, 7, 45, "PRESENTATION"));
        assertEquals(11L, service.submitTalk(form()).id);
        verify(notifications).talkSubmitted("ada@example.com", "Legacy is a feeling");
    }

    // ---------------------------------------------------------------- speakers

    @Test
    void emailRegisteredConcurrentlyIsConflictInAnyServerLanguage() {
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(false);
        when(speakerDao.insert("Ada", "ada@example.com", null))
                .thenThrow(new DuplicateKeyException("FEIL: duplisert nøkkelverdi bryter unik-skranke"));
        assertStatus(HttpStatus.CONFLICT, () -> service.registerSpeaker(speakerForm()));
        verify(notifications, never()).speakerRegistered(anyString(), anyString());
    }

    @Test
    void duplicateEmailIsConflict() {
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.registerSpeaker(speakerForm()));
    }

    @Test
    void registeredSpeakerIsWelcomed() {
        when(speakerDao.insert("Ada", "ada@example.com", null)).thenReturn(3L);
        Map<String, Object> registered = service.registerSpeaker(speakerForm());
        assertEquals(3L, registered.get("id"));
        verify(notifications).speakerRegistered("ada@example.com", "Ada");
    }

    @Test
    void getSpeakerReturnsTheStoredProfile() {
        Map<String, Object> ada = speaker(1, "Ada", "ada@example.com");
        when(speakerDao.findById(1L)).thenReturn(ada);
        assertSame(ada, service.getSpeaker(1));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getSpeaker(2));
    }

    @Test
    void updateSpeakerValidatesNameEmailAndBio() {
        when(speakerDao.findById(1L)).thenReturn(speaker(1, "Ada", "ada@example.com"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.updateSpeaker(1, " ", "ada@example.com", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.updateSpeaker(1, "Ada", "not-an-email", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.updateSpeaker(1, "Ada", "ada@example.com", "x".repeat(501)));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.updateSpeaker(2, "Ada", "ada@example.com", null));
        verify(speakerDao, never()).update(anyLong(), any(), any(), any());
    }

    @Test
    void updateSpeakerAcceptsA500CharacterBioAndNoBio() {
        Map<String, Object> ada = speaker(1, "Ada", "ada@example.com");
        when(speakerDao.findById(1L)).thenReturn(ada);
        assertSame(ada, service.updateSpeaker(1, "Ada", "ada@example.com", "x".repeat(500)));
        assertSame(ada, service.updateSpeaker(1, "Ada", "ada@example.com", null));
        verify(speakerDao).update(1, "Ada", "ada@example.com", "x".repeat(500));
        verify(speakerDao).update(1, "Ada", "ada@example.com", null);
    }

    @Test
    void updateSpeakerKeepingTheEmailInAnotherCaseIsNotAConflict() {
        when(speakerDao.findById(1L)).thenReturn(speaker(1, "Ada", "ada@example.com"));
        when(speakerDao.emailTaken("ADA@Example.com")).thenReturn(true); // taken by Ada herself
        service.updateSpeaker(1, "Ada", "ADA@Example.com", null);
        verify(speakerDao).update(1, "Ada", "ADA@Example.com", null);
    }

    @Test
    void updateSpeakerToAnotherSpeakersEmailIsConflict() {
        when(speakerDao.findById(1L)).thenReturn(speaker(1, "Ada", "ada@example.com"));
        when(speakerDao.emailTaken("grace@example.com")).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.updateSpeaker(1, "Ada", "grace@example.com", null));
        verify(speakerDao, never()).update(anyLong(), any(), any(), any());

        when(speakerDao.emailTaken("alan@example.com")).thenReturn(false);
        when(speakerDao.update(1, "Ada", "alan@example.com", null)).thenThrow(new DuplicateKeyException("race"));
        assertStatus(HttpStatus.CONFLICT, () -> service.updateSpeaker(1, "Ada", "alan@example.com", null));
    }

    @Test
    void updateSpeakerReturnsTheProfileAsStoredAfterTheUpdate() {
        Map<String, Object> before = speaker(1, "Ada", "ada@example.com");
        Map<String, Object> after = speaker(1, "Ada L", "ada@example.org");
        when(speakerDao.findById(1L)).thenReturn(before, after);
        assertSame(after, service.updateSpeaker(1, "Ada L", "ada@example.org", null));
    }

    // ---------------------------------------------------------------- co-speakers

    @Test
    void databaseErrorsOtherThanLockTimeoutPropagateUnchanged() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("connection reset");
        when(talkDao.lock(5L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.addCoSpeaker(5, 6)));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.removeCoSpeaker(5, 6)));
    }

    // ---------------------------------------------------------------- ratings

    @Test
    void ratingNeedsAPositiveReviewerAndAWholeScoreFrom1To5() {
        when(talkDao.exists(5L)).thenReturn(true);
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 0, score(3)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, -1, score(3)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 1, score(0)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 1, score(6)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 1, score("3")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 1, score(3.0)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.rate(5, 1, new HashMap<>()));
        verify(ratingDao, never()).save(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void ratingAnUnknownTalkIsNotFound() {
        when(talkDao.exists(5L)).thenReturn(false);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.rate(5, 1, score(3)));
        verify(ratingDao, never()).save(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void ratingSavesAndEchoesBoundaryScores() {
        when(talkDao.exists(5L)).thenReturn(true);
        for (int s : new int[] {1, 5}) {
            Map<String, Object> result = service.rate(5, 1, score(s));
            assertEquals(5L, result.get("talkId"));
            assertEquals(1L, result.get("reviewerId"));
            assertEquals(s, result.get("score"));
            assertTrue(((String) result.get("ratedAtDisplay")).matches("\\d{1,2}/\\d{1,2}/\\d{2}, \\d{1,2}:\\d{2} [AP]M"),
                    (String) result.get("ratedAtDisplay"));
            verify(ratingDao).save(eq(5L), eq(1L), eq(s), anyString());
        }
    }

    // ---------------------------------------------------------------- listing and ranking

    @Test
    void listIsOldestFirstWithoutSortAndRejectsUnknownSorts() {
        givenTalks(rated(1, 3, 2), rated(2, 5, 1));
        assertEquals(Arrays.asList(1L, 2L), ids(service.listTalks(null, null)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.listTalks("title", null));
    }

    @Test
    void listForOneSpeakerUsesTheSpeakersTalks() {
        TalkDto t = talk(9, 4, 45, "PRESENTATION");
        when(talkDao.findBySpeaker(4L)).thenReturn(new ArrayList<>(Collections.singletonList(t)));
        assertEquals(Collections.singletonList(9L), ids(service.listTalks(null, 4L)));
    }

    // Ratings spec: best average first, then more reviewers, then oldest first; unrated talks last.
    @Test
    void rankingIsByAverageThenRatingCountThenOldestWithUnratedLast() {
        // Deliberately not in id order, so "oldest first" has to come from the comparator.
        givenTalks(
                rated(7, 0, 0),          // unrated
                rated(6, 4, 1),          // avg 4.0, 1 rating
                rated(3, 0, 0),          // unrated
                rated(5, 8, 2),          // avg 4.0, 2 ratings
                rated(4, 4, 1),          // avg 4.0, 1 rating, older than 6
                rated(2, 9, 2),          // avg 4.5
                rated(1, 2, 1));         // avg 2.0
        assertEquals(Arrays.asList(2L, 5L, 4L, 6L, 1L, 7L, 3L), ids(service.listTalks("rating", null)));
    }

    @Test
    void averageIsRoundedToOneDecimalHalfUp() {
        givenTalks(rated(1, 17, 4)); // 4, 4, 4, 5 -> 4.25 -> 4.3
        TalkDto t = service.listTalks(null, null).get(0);
        assertEquals(4.3, t.averageRating);
        assertEquals(4, t.ratingCount);
    }

    // ---------------------------------------------------------------- statistics

    @Test
    void statisticsCountTalksSpeakersHoursAndAverageRating() {
        TalkDto a = rated(1, 4, 1);              // speaker 1, PRESENTATION 45, avg 4.0
        TalkDto b = rated(2, 7, 2);              // speaker 1, WORKSHOP 120, avg 3.5
        b.format = "WORKSHOP";
        b.durationMinutes = 120;
        TalkDto c = rated(3, 0, 0);              // speaker 2 (deleted), LIGHTNING_10, unrated
        c.speakerId = 2L;
        c.format = "LIGHTNING_10";
        c.durationMinutes = 10;
        c.level = "ADVANCED";
        TalkDto d = rated(4, 0, 0);              // speaker 3, PRESENTATION 45, unrated
        d.speakerId = 3L;
        givenTalks(a, b, c, d);
        when(speakerDao.findById(1L)).thenReturn(speaker(1, "Ada", "ada@example.com"));
        when(speakerDao.findById(3L)).thenReturn(speaker(3, "Grace", "grace@example.com"));
        when(speakerDao.count()).thenReturn(5);

        Map<String, Object> stats = service.statistics();

        assertEquals(5, stats.get("speakers"));
        assertEquals(2, stats.get("speakersWithTalks"));
        assertEquals(4, stats.get("talks"));
        assertEquals(map("PRESENTATION", 2, "WORKSHOP", 1, "LIGHTNING_10", 1), stats.get("byFormat"));
        assertEquals(map("INTERMEDIATE", 3, "ADVANCED", 1), stats.get("byLevel"));
        assertEquals(map("SUBMITTED", 4), stats.get("byStatus"));
        assertEquals(220 / 60.0, stats.get("totalHours"));
        assertEquals(3.8, stats.get("averageRating")); // (4.0 + 3.5) / 2 = 3.75
        assertTrue(((String) stats.get("generated")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void statisticsHaveNoAverageWhenNothingIsRated() {
        givenTalks(rated(1, 0, 0));
        assertNull(service.statistics().get("averageRating"));
    }

    // ---------------------------------------------------------------- helpers

    private void givenTalks(TalkDto... talks) {
        when(talkDao.findAll()).thenReturn(new ArrayList<>(Arrays.asList(talks)));
    }

    /** A talk whose ratings sum to {@code total} over {@code count} reviewers. */
    private TalkDto rated(long id, long total, int count) {
        when(ratingDao.stats(id)).thenReturn(stats(count, total));
        return talk(id, 1, 45, "PRESENTATION");
    }

    private static TalkDto talk(long id, long speakerId, int minutes, String format) {
        TalkDto t = new TalkDto();
        t.id = id;
        t.title = "Talk " + id;
        t.format = format;
        t.level = "INTERMEDIATE";
        t.status = "SUBMITTED";
        t.speakerId = speakerId;
        t.durationMinutes = minutes;
        t.submittedAt = new Date(0);
        return t;
    }

    private static Map<String, Object> stats(int count, long total) {
        Map<String, Object> m = new HashMap<>();
        m.put("cnt", count);
        m.put("total", total);
        return m;
    }

    private static Map<String, Object> speaker(long id, String name, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("bio", null);
        return m;
    }

    private static Map<String, Object> score(Object score) {
        Map<String, Object> m = new HashMap<>();
        m.put("score", score);
        return m;
    }

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static List<Long> ids(List<TalkDto> talks) {
        return talks.stream().map(t -> t.id).collect(Collectors.toList());
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, call).getStatusCode());
    }

    private static SpeakerForm speakerForm() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("ada@example.com");
        return form;
    }

    private static TalkForm form() {
        TalkForm form = new TalkForm();
        form.title = "Legacy is a feeling";
        form.format = "PRESENTATION";
        form.level = "INTERMEDIATE";
        form.speakerId = 7L;
        return form;
    }
}
