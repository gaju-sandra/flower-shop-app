package rw.bloomco.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Text;
import rw.bloomco.common.UploadService;
import rw.bloomco.common.Validation;
import rw.bloomco.security.AuthUser;

/**
 *   /api/profile    - any signed-in user (own account)
 *   /api/addresses  - customers' saved delivery addresses
 *   /api/customers  - staff read, admin manage
 *   /api/staff      - admin only
 */
@RestController
public class UserController {

    private static final String STAFF_ROLES = "order_manager|delivery_staff|customer_support|inventory_staff";
    private static final Set<String> USER_STATUS = Set.of("active", "disabled");

    public record ProfileBody(
            @Size(min = 2, max = 60, message = "Name is too short") String firstName,
            @Size(min = 2, max = 60, message = "Name is too short") String lastName,
            @Email(message = Validation.EMAIL_MESSAGE) String email,
            @Pattern(regexp = Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone) {}

    public record PasswordBody(
            String currentPassword,
            @NotBlank(message = Validation.PASSWORD_MESSAGE) @Pattern(regexp = Validation.PASSWORD_REGEX, message = Validation.PASSWORD_MESSAGE) String newPassword,
            String confirmPassword) {
        @AssertTrue(message = "Passwords do not match")
        public boolean isConfirmPassword() {
            return newPassword != null && newPassword.equals(confirmPassword);
        }
    }

    public record AddressBody(
            @Size(max = 40) String label,
            @Size(max = 120) String recipientName,
            @Pattern(regexp = "^$|" + Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @NotBlank(message = "Province is required") @Size(min = 2, max = 60) String province,
            @NotBlank(message = "District is required") @Size(min = 2, max = 60) String district,
            @NotBlank(message = "Sector is required") @Size(min = 2, max = 60) String sector,
            @NotBlank(message = "Street / house address is required") @Size(min = 3, max = 200) String street,
            @Size(max = 300) String locationDescription,
            Boolean isDefault) {}

    public record AdminUserBody(
            @Size(min = 2, max = 60) String firstName,
            @Size(min = 2, max = 60) String lastName,
            @Email(message = Validation.EMAIL_MESSAGE) String email,
            @Pattern(regexp = Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @Pattern(regexp = STAFF_ROLES, message = "Unknown staff role") String staffRole,
            @Pattern(regexp = "active|disabled", message = "Status must be active or disabled") String status) {}

    public record StaffBody(
            @NotBlank(message = "First name is required") @Size(min = 2, max = 60) String firstName,
            @NotBlank(message = "Last name is required") @Size(min = 2, max = 60) String lastName,
            @NotBlank(message = Validation.EMAIL_MESSAGE) @Email(message = Validation.EMAIL_MESSAGE) String email,
            @NotBlank(message = Validation.PHONE_MESSAGE) @Pattern(regexp = Validation.PHONE_REGEX, message = Validation.PHONE_MESSAGE) String phone,
            @NotBlank(message = Validation.PASSWORD_MESSAGE) @Pattern(regexp = Validation.PASSWORD_REGEX, message = Validation.PASSWORD_MESSAGE) String password,
            @NotBlank(message = "Choose a staff role") @Pattern(regexp = STAFF_ROLES, message = "Unknown staff role") String staffRole) {}

    private final UserService users;
    private final UploadService uploads;

    public UserController(UserService users, UploadService uploads) {
        this.users = users;
        this.uploads = uploads;
    }

    // =============== /api/profile ===============

    @PutMapping("/api/profile")
    @PreAuthorize("hasAuthority('profile:manage')")
    public Map<String, Object> updateProfile(@Valid @RequestBody ProfileBody body, @AuthenticationPrincipal AuthUser user) {
        return users.updateProfile(user.id(), body);
    }

    @PostMapping("/api/profile/avatar")
    @PreAuthorize("hasAuthority('profile:manage')")
    public Map<String, Object> avatar(@RequestPart(name = "avatar", required = false) MultipartFile file, @AuthenticationPrincipal AuthUser user) {
        String url = uploads.saveImage(file);
        if (url == null) throw ApiException.badRequest("Choose an image to upload");
        return users.setAvatar(user.id(), url);
    }

    @PutMapping("/api/profile/password")
    @PreAuthorize("hasAuthority('profile:manage')")
    public Map<String, Object> password(@Valid @RequestBody PasswordBody body, @AuthenticationPrincipal AuthUser user) {
        users.changePassword(user.id(), body.currentPassword(), body.newPassword());
        return Json.obj("message", "Password updated");
    }

    @GetMapping("/api/profile/dashboard")
    @PreAuthorize("hasAuthority('profile:manage') and hasAuthority('orders:read:own')")
    public Map<String, Object> dashboard(@AuthenticationPrincipal AuthUser user) {
        return users.customerDashboard(user.id());
    }

    // =============== /api/addresses ===============

    @GetMapping("/api/addresses")
    @PreAuthorize("hasAuthority('addresses:manage')")
    public List<Map<String, Object>> addresses(@AuthenticationPrincipal AuthUser user) {
        return users.addresses(user.id());
    }

    @PostMapping("/api/addresses")
    @PreAuthorize("hasAuthority('addresses:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> addAddress(@Valid @RequestBody AddressBody body, @AuthenticationPrincipal AuthUser user) {
        return users.saveAddress(user.id(), body, null);
    }

    @PutMapping("/api/addresses/{id}")
    @PreAuthorize("hasAuthority('addresses:manage')")
    public List<Map<String, Object>> editAddress(@PathVariable int id, @Valid @RequestBody AddressBody body, @AuthenticationPrincipal AuthUser user) {
        return users.saveAddress(user.id(), body, id);
    }

    @PatchMapping("/api/addresses/{id}/default")
    @PreAuthorize("hasAuthority('addresses:manage')")
    public List<Map<String, Object>> defaultAddress(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        return users.setDefaultAddress(user.id(), id);
    }

    @DeleteMapping("/api/addresses/{id}")
    @PreAuthorize("hasAuthority('addresses:manage')")
    public List<Map<String, Object>> deleteAddress(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        return users.deleteAddress(user.id(), id);
    }

    // =============== /api/customers ===============

    @GetMapping("/api/customers")
    @PreAuthorize("hasAuthority('customers:read')")
    public Map<String, Object> customers(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        return users.list("customer", search, Text.oneOf(status, USER_STATUS, "status"), Paging.of(page, limit, 15, 100));
    }

    @GetMapping("/api/customers/{id}")
    @PreAuthorize("hasAuthority('customers:read')")
    public Map<String, Object> customer(@PathVariable int id) {
        return users.customerDetail(id);
    }

    @PutMapping("/api/customers/{id}")
    @PreAuthorize("hasAuthority('customers:manage')")
    public Map<String, Object> editCustomer(@PathVariable int id, @Valid @RequestBody AdminUserBody body, @AuthenticationPrincipal AuthUser user) {
        return users.adminUpdate(id, new AdminUserBody(body.firstName(), body.lastName(), body.email(), body.phone(), null, body.status()),
                user.id(), "customer");
    }

    // =============== /api/staff ===============

    @GetMapping("/api/staff")
    @PreAuthorize("hasAuthority('staff:manage')")
    public Map<String, Object> staff(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        return users.list("staff", search, Text.oneOf(status, USER_STATUS, "status"), Paging.of(page, limit, 20, 100));
    }

    @PostMapping("/api/staff")
    @PreAuthorize("hasAuthority('staff:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addStaff(@Valid @RequestBody StaffBody body, @AuthenticationPrincipal AuthUser user) {
        return users.createStaff(body, user.id());
    }

    @PutMapping("/api/staff/{id}")
    @PreAuthorize("hasAuthority('staff:manage')")
    public Map<String, Object> editStaff(@PathVariable int id, @Valid @RequestBody AdminUserBody body, @AuthenticationPrincipal AuthUser user) {
        return users.adminUpdate(id, body, user.id(), "staff");
    }

    @DeleteMapping("/api/staff/{id}")
    @PreAuthorize("hasAuthority('staff:manage')")
    public ResponseEntity<Void> deleteStaff(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        users.deleteStaff(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
