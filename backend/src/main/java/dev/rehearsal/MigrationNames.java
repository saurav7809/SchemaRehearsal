package dev.rehearsal;

import org.flywaydb.core.api.MigrationVersion;

import java.util.regex.Pattern;

final class MigrationNames {
    private static final String DESCRIPTION = "[a-zA-Z0-9_. -]+\\.sql";
    private static final Pattern VERSIONED = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__" + DESCRIPTION);
    private static final Pattern REPEATABLE = Pattern.compile("R__" + DESCRIPTION);

    private MigrationNames() {}

    static MigrationVersion version(String name) {
        var match = VERSIONED.matcher(name);
        return match.matches() ? MigrationVersion.fromVersion(match.group(1)) : null;
    }

    static boolean isRepeatable(String name) { return REPEATABLE.matcher(name).matches(); }

    static String repeatableDescription(String name) {
        return name.substring(3,name.length()-4).replace('_',' ').trim();
    }
}
