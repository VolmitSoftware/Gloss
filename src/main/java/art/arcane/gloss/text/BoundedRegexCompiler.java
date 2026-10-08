package art.arcane.gloss.text;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

public final class BoundedRegexCompiler {
    private BoundedRegexCompiler() {
    }

    public static Pattern compile(String source, Limits limits, String owner) {
        if (source.length() > limits.maxPatternCharacters()) {
            throw new IllegalArgumentException(owner + " exceeds maxPatternCharacters");
        }
        estimate(source, limits, owner);
        try {
            Pattern pattern = Pattern.compile(source);
            if (pattern.programSize() > limits.maxProgramSize()) {
                throw new IllegalArgumentException(owner + " exceeds maxProgramSize");
            }
            return pattern;
        } catch (PatternSyntaxException failure) {
            throw new IllegalArgumentException(owner + " is not supported RE2 syntax: " + failure.getDescription()
                + ". Rewrite lookaround, backreferences, atomic groups, or unsupported flags before importing.");
        }
    }

    public record Limits(int maxPatternCharacters, int maxProgramSize, int maxNestingDepth) {
        public Limits {
            if (maxPatternCharacters < 1 || maxPatternCharacters > 4096 || maxProgramSize < 16
                || maxProgramSize > 1000000 || maxNestingDepth < 1 || maxNestingDepth > 128) {
                throw new IllegalArgumentException("Invalid bounded regular expression limits");
            }
        }
    }

    private static void estimate(String pattern, Limits limits, String owner) {
        long[] sizes = new long[limits.maxNestingDepth() + 1];
        long[] atoms = new long[sizes.length];
        int depth = 0;
        for (int index = 0; index < pattern.length(); index++) {
            char value = pattern.charAt(index);
            if (value == '\\' && index + 1 < pattern.length()) {
                char escaped = pattern.charAt(++index);
                if (escaped == 'Q') {
                    int end = pattern.indexOf("\\E", index + 1);
                    int stop = end < 0 ? pattern.length() : end;
                    atoms[depth] = Math.max(2L, (stop - index - 1L) * 2L);
                    sizes[depth] += atoms[depth];
                    index = end < 0 ? pattern.length() : end + 1;
                } else {
                    if ((escaped == 'p' || escaped == 'P' || escaped == 'x')
                        && index + 1 < pattern.length() && pattern.charAt(index + 1) == '{') {
                        int end = pattern.indexOf('}', index + 2);
                        index = end < 0 ? pattern.length() : end;
                    }
                    atoms[depth] = 2;
                    sizes[depth] += 2;
                }
            } else if (value == '[') {
                index = classEnd(pattern, index);
                atoms[depth] = 2;
                sizes[depth] += 2;
            } else if (value == '(') {
                if (++depth > limits.maxNestingDepth()) {
                    throw new IllegalArgumentException(owner + " exceeds maxNestingDepth");
                }
                sizes[depth] = 2;
                atoms[depth] = 0;
            } else if (value == ')' && depth > 0) {
                long group = sizes[depth--] + 2;
                atoms[depth] = group;
                sizes[depth] += group;
            } else if (value == '{') {
                Repetition repetition = repetition(pattern, index);
                if (repetition != null) {
                    long expanded = atoms[depth] * repetition.copies() + 2;
                    sizes[depth] += expanded - atoms[depth];
                    atoms[depth] = expanded;
                    index = repetition.end();
                } else {
                    atoms[depth] = 2;
                    sizes[depth] += 2;
                }
            } else if (value == '*' || value == '+' || value == '?') {
                sizes[depth] += 2;
                atoms[depth] += 2;
            } else {
                atoms[depth] = 2;
                sizes[depth] += 2;
            }
            if (sizes[depth] > limits.maxProgramSize()) {
                throw new IllegalArgumentException(owner + " exceeds the conservative maxProgramSize bound");
            }
        }
    }

    private static int classEnd(String pattern, int start) {
        int index = start + 1;
        if (index < pattern.length() && pattern.charAt(index) == '^') {
            index++;
        }
        if (index < pattern.length() && pattern.charAt(index) == ']') {
            index++;
        }
        for (; index < pattern.length(); index++) {
            if (pattern.charAt(index) == '\\') {
                index++;
            } else if (pattern.charAt(index) == ']') {
                return index;
            }
        }
        return pattern.length();
    }

    private static Repetition repetition(String pattern, int start) {
        int end = pattern.indexOf('}', start + 1);
        if (end < 0) {
            return null;
        }
        int comma = -1;
        for (int index = start + 1; index < end; index++) {
            char value = pattern.charAt(index);
            if (value == ',' && comma < 0) {
                comma = index;
            } else if (value < '0' || value > '9') {
                return null;
            }
        }
        int minimumEnd = comma < 0 ? end : comma;
        if (minimumEnd == start + 1) {
            return null;
        }
        int minimum = boundedCount(pattern.substring(start + 1, minimumEnd));
        int maximum = comma < 0 ? minimum
            : comma == end - 1 ? minimum + 1 : boundedCount(pattern.substring(comma + 1, end));
        return new Repetition(end, Math.max(1, maximum));
    }

    private static int boundedCount(String value) {
        if (value.length() > 4) {
            return 1001;
        }
        return Math.min(1001, Integer.parseInt(value));
    }

    private record Repetition(int end, int copies) {
    }
}
