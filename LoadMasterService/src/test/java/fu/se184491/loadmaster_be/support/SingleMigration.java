package fu.se184491.loadmaster_be.support;

import java.io.IOException;
import java.nio.file.Files;

/** Test-only location for one unchanged production SQL resource; not a full-history rollout test. */
public final class SingleMigration {
    private SingleMigration() {}
    public static String location(String name) {
        if (!name.matches("V[0-9]+__[A-Za-z0-9_]+\\.sql")) throw new IllegalArgumentException("Migration filename required");
        try (var input = SingleMigration.class.getResourceAsStream("/db/migration/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing migration resource: " + name);
            var directory = Files.createTempDirectory("loadmaster-single-migration-");
            var target = directory.resolve(name);
            Files.write(target, input.readAllBytes());
            directory.toFile().deleteOnExit();
            target.toFile().deleteOnExit();
            return "filesystem:" + directory.toAbsolutePath();
        } catch (IOException exception) { throw new IllegalStateException("Cannot stage isolated migration fixture", exception); }
    }
}
