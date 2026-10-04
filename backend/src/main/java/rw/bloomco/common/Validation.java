package rw.bloomco.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Shared request fragments and rules (mirrors the client-side checks). */
public final class Validation {

    private Validation() {}

    public static final String PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE =
            "Password must be at least 8 characters and include an uppercase letter, a lowercase letter and a number";
    /** Rwandan mobile number; spaces or dashes are allowed and stripped before storing. */
    public static final String PHONE_REGEX = "^(\\+?250|0)[\\s-]?7[\\s-]?[2389]([\\s-]?\\d){7}$";
    public static final String PHONE_MESSAGE = Text.PHONE_MESSAGE;
    public static final String EMAIL_MESSAGE = "Enter a valid email address";

    /** Delivery / home address. */
    public record Address(
            @NotBlank(message = "Province is required") @Size(min = 2, max = 60, message = "Province is required") String province,
            @NotBlank(message = "District is required") @Size(min = 2, max = 60, message = "District is required") String district,
            @NotBlank(message = "Sector is required") @Size(min = 2, max = 60, message = "Sector is required") String sector,
            @NotBlank(message = "Street / house address is required") @Size(min = 3, max = 200, message = "Street / house address is required") String street,
            @Size(max = 300) String locationDescription) {}
}
