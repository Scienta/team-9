package no.scienta.cfp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Programme export: workflow/specs/programme-export.md (rules R1-R8, decisions D1-D8). The content type is set on the
 * response, so Spring skips content negotiation: JSON and CSV whatever the Accept header (R5, D3).
 */
@RestController
public class ProgrammeExportController {

    static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String HEADER = "slot,start,end,room,talkId,title,format,speakers\n";

    private final Export export;

    public ProgrammeExportController(Export export) {
        this.export = export;
    }

    @GetMapping("/programme")
    public ResponseEntity<List<Map<String, Object>>> json() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(export.rows());
    }

    @GetMapping("/programme.csv")
    public ResponseEntity<byte[]> csv() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (Map<String, Object> row : export.rows()) {
            @SuppressWarnings("unchecked")
            List<String> speakers = (List<String>) row.get("speakers");
            sb.append(row.get("slot")).append(',')
                    .append(row.get("start")).append(',')
                    .append(row.get("end")).append(',')
                    .append(row.get("room")).append(',')
                    .append(row.get("talkId")).append(',')
                    .append(field((String) row.get("title"))).append(',')
                    .append(field((String) row.get("format"))).append(',')
                    .append(field(String.join("; ", speakers)))
                    .append('\n');
        }
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=programme.csv")
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 4180: quoted only if it has a comma, a quote or a line break, quotes doubled (R7). */
    static String field(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Service
    public static class Export {

        private final ProgrammeExportDao dao;

        public Export(ProgrammeExportDao dao) {
            this.dao = dao;
        }

        /** One snapshot: both queries see the same programme and co-speakers. */
        @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> rows = dao.rows();
            Map<Long, List<String>> coSpeakers = dao.coSpeakerNames();
            List<Map<String, Object>> out = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                String slot = (String) r.get("slot");
                long talkId = ((Number) r.get("talk_id")).longValue();
                List<String> speakers = new ArrayList<>();
                speakers.add((String) r.get("speaker"));
                speakers.addAll(coSpeakers.getOrDefault(talkId, Collections.emptyList()));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", slot);
                row.put("start", Programme.SLOTS.get(slot)[0]);
                row.put("end", Programme.SLOTS.get(slot)[1]);
                row.put("room", r.get("room"));
                row.put("talkId", talkId);
                row.put("title", r.get("title"));
                row.put("format", r.get("format"));
                row.put("speakers", speakers);
                out.add(row);
            }
            return out;
        }
    }
}
