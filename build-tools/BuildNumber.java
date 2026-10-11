import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildNumber {

    private BuildNumber() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: BuildNumber <prepare|commit> <properties> <prepared> <session>");
        }

        Path properties = Path.of(args[1]).toAbsolutePath();
        Path prepared = Path.of(args[2]).toAbsolutePath();

        switch (args[0]) {
            case "prepare" -> prepare(properties, prepared, args[3]);
            case "commit" -> commit(properties, prepared, args[3]);
            default -> throw new IllegalArgumentException("Unknown operation: " + args[0]);
        }
    }

    private static void prepare(Path properties, Path prepared, String session) throws IOException {
        Map<String, String> existing = read(properties);
        BigInteger current = parseNumber(existing.getOrDefault("short.build.number", existing.get("build.count")));
        write(prepared, current.add(BigInteger.ONE), session);
    }

    private static void commit(Path properties, Path prepared, String session) throws IOException {
        Map<String, String> candidate = read(prepared);
        if (!session.equals(candidate.get("build.session"))) {
            throw new IllegalStateException("Prepared build number does not belong to this Maven session");
        }

        BigInteger next = parseNumber(candidate.get("short.build.number"));
        Path lockPath = properties.resolveSibling(properties.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, String> existing = read(properties);
            BigInteger current = parseNumber(existing.get("short.build.number"));
            if (current.equals(next) && session.equals(existing.get("build.session"))) {
                return;
            }
            if (current.compareTo(next) >= 0) {
                throw new IllegalStateException("This build number has already been committed by another Maven session");
            }
            write(properties, next, session);
        } finally {
            Files.deleteIfExists(lockPath);
        }
    }

    private static Map<String, String> read(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return values;
        }

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0 && !line.stripLeading().startsWith("#")) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return values;
    }

    private static BigInteger parseNumber(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            return BigInteger.ZERO;
        }
        return new BigInteger(value);
    }

    private static void write(Path destination, BigInteger number, String session) throws IOException {
        Files.createDirectories(destination.getParent());
        String year = String.format("%02d", Year.now(ZoneOffset.UTC).getValue() % 100);
        List<String> lines = List.of(
            "# Auto-generated build number for SlimefunCore.",
            "build.year=" + year,
            "build.count=" + number,
            "short.build.number=" + number,
            "generated.short.build.number=" + number,
            "build.session=" + session,
            ""
        );
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
