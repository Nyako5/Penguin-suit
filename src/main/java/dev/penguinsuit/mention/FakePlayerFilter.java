package dev.penguinsuit.mention;

import java.io.IOException;
import java.util.*;

/** Literal account-name rules, never display names or QQ nicknames. */
public final class FakePlayerFilter {
    public boolean enabled = true;
    public List<String> namePrefixes = List.of("bot_");
    public List<String> nameSuffixes = List.of("_fake");
    public List<String> exactNames = List.of();
    public List<String> excludedPlayerUuids = List.of();
    public boolean caseSensitive = false;
    public List<String> allowedPlayerUuids = List.of();

    public void validate() throws IOException {
        dev.penguinsuit.config.FeatureOptions.names(exactNames);
        dev.penguinsuit.config.FeatureOptions.uuidList(excludedPlayerUuids);
        if (namePrefixes == null || nameSuffixes == null || allowedPlayerUuids == null)
            throw new IOException("fakePlayerFilter lists must not be null");
        for (var list : List.of(namePrefixes, nameSuffixes)) for (String rule : list)
            if (rule == null || !rule.matches("[A-Za-z0-9_]{1,16}"))
                throw new IOException("fakePlayerFilter rules must contain 1-16 account-name characters");
        for (String value : allowedPlayerUuids) try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IOException("Invalid allowedPlayerUuids entry"); }
    }
    public boolean excludes(UUID uuid, String accountName) {
        if (!enabled || accountName == null) return false;
        if (uuid != null && allowedPlayerUuids.stream().anyMatch(s -> s.equalsIgnoreCase(uuid.toString()))) return false;
        if(uuid!=null&&excludedPlayerUuids.stream().anyMatch(s->s.equalsIgnoreCase(uuid.toString())))return true;
        String name = normalized(accountName);
        if(exactNames.stream().anyMatch(s->name.equals(normalized(s))))return true;
        return namePrefixes.stream().anyMatch(s -> name.startsWith(normalized(s)))
                || nameSuffixes.stream().anyMatch(s -> name.endsWith(normalized(s)));
    }
    private String normalized(String text) { return caseSensitive ? text : text.toLowerCase(Locale.ROOT); }
}
