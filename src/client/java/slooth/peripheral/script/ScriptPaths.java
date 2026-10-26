package slooth.peripheral.script;

import java.nio.file.*;
import java.io.IOException;

/** One boundary for script UI, command and API paths. Symlinks are deliberately unsupported. */
public final class ScriptPaths {
    private ScriptPaths() {}
    public static Path root() { return ScriptRunner.SCRIPTS_DIR.toAbsolutePath().normalize(); }
    public static Path resolve(String name) {
        try {
            Path rel = Path.of(name);
            if (rel.isAbsolute() || name.indexOf('\\') >= 0) throw new IllegalArgumentException("Use a relative script path");
            for (Path part : rel) if (part.toString().equals("..")) throw new IllegalArgumentException("Parent paths are not allowed");
            Path root = root(), target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("Path is outside the scripts folder");
            if (Files.exists(root) && !root.toRealPath().equals(root)) throw new IllegalArgumentException("Linked scripts folders are not supported");
            Path current = root;
            for (Path part : root.relativize(target)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Linked files and folders are not supported");
            }
            return target;
        } catch (InvalidPathException | IOException e) { throw new IllegalArgumentException("Invalid script path", e); }
    }
    public static boolean exists(String name) { try { return Files.exists(resolve(name)); } catch(IllegalArgumentException e) { return false; } }
    public static String name(String name) { return root().relativize(resolve(name)).toString().replace('\\', '/'); }
    public static Path script(String name) {
        Path p = resolve(name);
        if (!name.endsWith(".py") || p.getFileName().toString().equals("mc.py") || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Choose an existing Python script (.py)");
        return p;
    }
}
