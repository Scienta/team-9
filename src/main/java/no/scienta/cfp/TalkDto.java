package no.scienta.cfp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * A talk as the API returns it. Also used internally all over the place.
 */
public class TalkDto {

    public Long id;
    public String title;
    public String format;
    public String level;
    public Long speakerId;
    public List<Long> coSpeakerIds = new ArrayList<>();
    public Integer durationMinutes;
    public String status;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'", timezone = "UTC")
    public Date submittedAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    public LocalDate submittedOn;  // the reporting tool wants a plain date
    public Double averageRating;
    public int ratingCount;
}
