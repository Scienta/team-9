package no.scienta.cfp;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * POST /workshops/{id}/registrations. Validated like SpeakerForm, so both accept the same emails (D1), but strictly
 * typed: a number or boolean is a 400, not a string (D9). Postgres refuses NUL characters, so they're a 400 here
 * rather than a 500 at the insert.
 */
public class RegistrationForm {

    @NotBlank
    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String name;

    @NotBlank
    @Email
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String email;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
