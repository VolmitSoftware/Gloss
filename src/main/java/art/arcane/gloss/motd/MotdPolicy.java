package art.arcane.gloss.motd;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

public final class MotdPolicy {
    private MotdPolicy() {
    }

    public static List<String> icons(List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        if (paths.size() > 64) {
            throw new IllegalArgumentException("MOTD icon sets support at most 64 images");
        }
        List<String> copied = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("MOTD icon paths must not be blank");
            }
            copied.add(path.trim());
        }
        return List.copyOf(copied);
    }

    public static String sampleMode(String mode, List<String> sample) {
        String normalized = mode == null ? (sample.isEmpty() ? "inherit" : "replace") : mode;
        if (!Set.of("inherit", "replace", "hide").contains(normalized)) {
            throw new IllegalArgumentException("MOTD sampleMode must be inherit, replace or hide");
        }
        return normalized;
    }

    public static int count(String mode, int value, int original) {
        return switch (mode) {
            case "fixed" -> Math.max(0, value);
            case "offset" -> (int) Math.clamp((long) original + value, 0L, Integer.MAX_VALUE);
            default -> Math.max(0, original);
        };
    }

    public record Rotation(String mode, Long intervalSeconds) {
        public static final Rotation DEFAULT = new Rotation("weighted", 60L);

        public Rotation {
            mode = mode == null ? "weighted" : mode;
            intervalSeconds = intervalSeconds == null ? 60L : intervalSeconds;
            if (!Set.of("weighted", "sequence", "time", "first").contains(mode)) {
                throw new IllegalArgumentException("MOTD rotation mode must be weighted, sequence, time or first");
            }
            if (intervalSeconds < 1L || intervalSeconds > 86400L) {
                throw new IllegalArgumentException("MOTD rotation intervalSeconds must be within 1..86400");
            }
        }

        public long position(AtomicLong sequence, long epochMillis) {
            return mode.equals("time") ? Math.floorDiv(epochMillis, intervalSeconds * 1000L)
                : mode.equals("sequence") ? sequence.getAndIncrement() : 0L;
        }

        public int choose(List<Candidate> entries, Request request, long position, RandomGenerator random) {
            long total = 0L;
            int selected = -1;
            int eligible = 0;
            for (Candidate entry : entries) {
                if (!entry.selector().matchesRequest(request)) {
                    continue;
                }
                if (mode.equals("first")) {
                    return entry.index();
                }
                eligible++;
                if (mode.equals("weighted")) {
                    total += entry.weight();
                    if (random.nextLong(total) < entry.weight()) {
                        selected = entry.index();
                    }
                }
            }
            if (mode.equals("weighted") || eligible == 0) {
                return selected;
            }
            int target = (int) Math.floorMod(position, (long) eligible);
            for (Candidate entry : entries) {
                if (entry.selector().matchesRequest(request) && target-- == 0) {
                    return entry.index();
                }
            }
            return -1;
        }

        public int icon(int count, long position, RandomGenerator random) {
            if (count == 0) {
                return -1;
            }
            return switch (mode) {
                case "weighted" -> random.nextInt(count);
                case "first" -> 0;
                default -> (int) Math.floorMod(position, (long) count);
            };
        }
    }

    public record Selector(List<String> hostnames, Integer minProtocol, Integer maxProtocol,
                           String zone, String startTime, String endTime, List<Integer> days,
                           List<String> states, Integer minOnline, Integer maxOnline) {
        public static final Selector ANY = new Selector(null, null, null, null, null, null, null, null, null, null);

        public Selector {
            hostnames = hostnames == null ? List.of() : List.copyOf(hostnames);
            List<String> normalized = new ArrayList<>(hostnames.size());
            for (String hostname : hostnames) {
                String host = hostname.trim().toLowerCase(Locale.ROOT);
                String suffix = host.startsWith("*.") ? host.substring(2) : host;
                if (suffix.isEmpty() || suffix.contains("*") || suffix.contains("/") || host.length() > 253) {
                    throw new IllegalArgumentException("MOTD hostnames require an exact host or *.suffix");
                }
                normalized.add(host);
            }
            hostnames = List.copyOf(normalized);
            if (minProtocol != null && minProtocol < 0 || maxProtocol != null && maxProtocol < 0
                || minProtocol != null && maxProtocol != null && minProtocol > maxProtocol) {
                throw new IllegalArgumentException("MOTD protocol range is invalid");
            }
            zone = zone == null ? "UTC" : zone;
            ZoneId.of(zone);
            if ((startTime == null) != (endTime == null)) {
                throw new IllegalArgumentException("MOTD time selection requires startTime and endTime");
            }
            if (startTime != null) {
                LocalTime.parse(startTime);
                LocalTime.parse(endTime);
            }
            days = days == null ? List.of() : List.copyOf(days);
            for (int day : days) {
                if (day < 1 || day > 7) {
                    throw new IllegalArgumentException("MOTD days must be within 1..7 (Monday..Sunday)");
                }
            }
            states = states == null ? List.of() : List.copyOf(states);
            if (minOnline != null && minOnline < 0 || maxOnline != null && maxOnline < 0
                || minOnline != null && maxOnline != null && minOnline > maxOnline) {
                throw new IllegalArgumentException("MOTD online range is invalid");
            }
        }

        public boolean matchesRequest(Request request) {
            if ((minProtocol != null || maxProtocol != null) && request.protocol() == null) {
                return false;
            }
            if (minProtocol != null && request.protocol() < minProtocol
                || maxProtocol != null && request.protocol() > maxProtocol) {
                return false;
            }
            if (hostnames.isEmpty()) {
                return true;
            }
            for (String hostname : hostnames) {
                if (hostname.startsWith("*.")) {
                    String suffix = hostname.substring(1);
                    if (request.hostname().endsWith(suffix) && request.hostname().length() > suffix.length()) {
                        return true;
                    }
                } else if (hostname.equals(request.hostname())) {
                    return true;
                }
            }
            return false;
        }

        public boolean matchesSnapshot(long epochMillis, String state, int online) {
            if (!states.isEmpty() && !states.contains(state)
                || minOnline != null && online < minOnline || maxOnline != null && online > maxOnline) {
                return false;
            }
            if (days.isEmpty() && startTime == null) {
                return true;
            }
            ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zone));
            if (!days.isEmpty() && !days.contains(now.getDayOfWeek().getValue())) {
                return false;
            }
            if (startTime == null) {
                return true;
            }
            LocalTime start = LocalTime.parse(startTime);
            LocalTime end = LocalTime.parse(endTime);
            LocalTime time = now.toLocalTime();
            return start.equals(end) || (start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end));
        }
    }

    public record Counts(String onlineMode, Integer onlineValue, String maximumMode, Integer maximumValue,
                         Boolean hide) {
        public static final Counts DEFAULT = new Counts(null, null, null, null, null);

        public Counts {
            onlineMode = onlineMode == null ? "inherit" : onlineMode;
            maximumMode = maximumMode == null ? "inherit" : maximumMode;
            onlineValue = onlineValue == null ? 0 : onlineValue;
            maximumValue = maximumValue == null ? 0 : maximumValue;
            hide = Boolean.TRUE.equals(hide);
            if (!Set.of("inherit", "fixed", "offset").contains(onlineMode)
                || !Set.of("inherit", "fixed", "offset").contains(maximumMode)) {
                throw new IllegalArgumentException("MOTD count mode must be inherit, fixed or offset");
            }
            if (onlineMode.equals("fixed") && onlineValue < 0 || maximumMode.equals("fixed") && maximumValue < 0) {
                throw new IllegalArgumentException("MOTD fixed counts must not be negative");
            }
        }
    }

    public record Request(String hostname, Integer protocol) {
        public Request {
            hostname = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
            int separator = hostname.indexOf('\0');
            if (separator >= 0) {
                hostname = hostname.substring(0, separator);
            }
            if (hostname.endsWith(".")) {
                hostname = hostname.substring(0, hostname.length() - 1);
            }
        }
    }

    public record Candidate(int index, int weight, Selector selector) {
    }
}
