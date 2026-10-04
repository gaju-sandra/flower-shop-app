package rw.bloomco.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The authenticated principal taken from the access token. Authorities = RBAC permissions. */
public record AuthUser(int id, String role, String name) {

    public Collection<? extends GrantedAuthority> authorities() {
        List<SimpleGrantedAuthority> list = new java.util.ArrayList<>(
                Permissions.forRole(role).stream().map(SimpleGrantedAuthority::new).toList());
        list.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        return list;
    }

    public boolean can(String permission) {
        return Permissions.has(role, permission);
    }
}
