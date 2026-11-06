package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** There is no SMTP relay any more: with mail enabled a notification is one MAIL log line, otherwise nothing. */
class NotificationServiceTest {

    @Test
    void enabledMailLogsOneLinePerNotification() {
        NotificationService mail = service(true);
        try (LogCapture log = new LogCapture(NotificationService.class.getName())) {
            mail.talkSubmitted("ada@example.com", "Legacy is a feeling");
            mail.speakerRegistered("grace@example.com", "Grace");
            assertEquals(Arrays.asList(
                    "MAIL from=cfp@example.com to=ada@example.com subject=Thank you for your submission",
                    "MAIL from=cfp@example.com to=grace@example.com subject=Welcome"), log.info());
        }
    }

    @Test
    void disabledMailSendsNothing() {
        NotificationService mail = service(false);
        try (LogCapture log = new LogCapture(NotificationService.class.getName())) {
            mail.talkSubmitted("ada@example.com", "Legacy is a feeling");
            mail.speakerRegistered("grace@example.com", "Grace");
            assertEquals(Collections.emptyList(), log.info());
        }
    }

    private static NotificationService service(boolean enabled) {
        NotificationService mail = new NotificationService();
        ReflectionTestUtils.setField(mail, "enabled", enabled);
        ReflectionTestUtils.setField(mail, "from", "cfp@example.com");
        return mail;
    }
}
