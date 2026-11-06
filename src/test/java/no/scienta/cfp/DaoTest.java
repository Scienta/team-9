package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DAO behaviour the API can't show: the unique email index and the 404 fallbacks hide a wrong answer from
 * emailTaken, exists and lock, so they're checked here against a real database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DaoTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Autowired
    SpeakerDao speakers;

    @Autowired
    TalkDao talks;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void speakerLookupsMatchOnlyRegisteredSpeakers() {
        String email = "ada-" + UUID.randomUUID() + "@example.com";
        long id = speakers.insert("Ada", email, "bio");

        Map<String, Object> row = speakers.findById(id);
        assertNotNull(row);
        assertEquals("Ada", row.get("name"));
        assertEquals(email, row.get("email"));
        assertEquals("bio", row.get("bio"));
        assertNull(speakers.findById(id + 1000));

        assertTrue(speakers.exists(id));
        assertFalse(speakers.exists(id + 1000));

        assertTrue(speakers.emailTaken(email.toUpperCase()));
        assertFalse(speakers.emailTaken("nobody-" + UUID.randomUUID() + "@example.com"));
    }

    @Test
    void updateAndCountReportRows() {
        int before = speakers.count();
        long id = speakers.insert("Grace", "grace-" + UUID.randomUUID() + "@example.com", null);
        assertEquals(before + 1, speakers.count());
        assertEquals(1, speakers.update(id, "Grace H", "grace-" + UUID.randomUUID() + "@example.com", null));
        assertEquals("Grace H", speakers.findById(id).get("name"));
        assertEquals(0, speakers.update(id + 1000, "Nobody", "nobody@example.com", null));
    }

    @Test
    void talkExistsAndLockOnlyForExistingTalks() {
        long speaker = speakers.insert("Alan", "alan-" + UUID.randomUUID() + "@example.com", null);
        Long talk = talks.insertIfBelow(3, "T", "PRESENTATION", 45, "BEGINNER", speaker, 0L);
        assertNotNull(talk);

        assertTrue(talks.exists(talk));
        assertFalse(talks.exists(talk + 1000));

        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertEquals(Boolean.TRUE, tx.execute(s -> talks.lock(talk)));
        assertEquals(Boolean.FALSE, tx.execute(s -> talks.lock(talk + 1000)));
    }
}
