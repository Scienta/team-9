package no.scienta.cfp;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

/** POST /workshops/{id}/registrations. Validated like SpeakerForm, so both accept the same emails (D1). */
public class RegistrationForm {

    @NotBlank
    private String name;

    @NotBlank
    @Email
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
