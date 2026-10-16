package com.ignite.gateway.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SSE subscription pattern (task-97): a tag path whose segments may contain {@code *}
 * (any characters within that one segment; a lone {@code *} is exactly one whole segment) and
 * whose last segment may be {@code **} (every leaf at any depth below the preceding segments).
 *
 * Examples: {@code [default]CR/Rooms/*}{@code /DP_1/Value}, {@code [default]CR/Rooms/*}{@code /HF_*}{@code /CFMFeedback},
 * {@code [default]CR/Rooms/1*}{@code /Status}, {@code [default]CR/**}. A path without any wildcard is literal.
 *
 * The provider is everything between the leading {@code [} and the first {@code ]}; it may not
 * contain {@code *}. Brackets after the provider are ordinary characters of a segment. The class
 * is pure (no Ignition runtime): the caller browses and asks {@link #matchesFolder} /
 * {@link #matchesLeaf} per node.
 */
public final class TagPattern {

    private final String provider;
    private final List<String> segments;       // without the trailing "**"
    private final List<Pattern> matchers;      // null entry = literal segment
    private final boolean recursive;           // trailing "**"

    private TagPattern(String provider, List<String> segments, boolean recursive) {
        this.provider = provider;
        this.segments = Collections.unmodifiableList(segments);
        this.recursive = recursive;
        List<Pattern> m = new ArrayList<>();
        for (String s : segments) {
            m.add(s.indexOf('*') < 0 ? null : Pattern.compile(globToRegex(s)));
        }
        this.matchers = m;
    }

    /** Parses a pattern; returns null when malformed ({@code **} not last, empty segment, bad provider, ".", ".."). */
    public static TagPattern parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (!s.startsWith("[")) {
            return null;
        }
        int close = s.indexOf(']');
        if (close < 2) {
            return null;
        }
        String provider = s.substring(1, close);
        if (provider.indexOf('*') >= 0) {
            return null;
        }
        String rest = s.substring(close + 1);
        List<String> segs = new ArrayList<>();
        boolean recursive = false;
        if (!rest.isEmpty()) {
            String[] parts = rest.split("/", -1);
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.isEmpty() || p.equals(".") || p.equals("..")) {
                    return null;
                }
                if (p.equals("**")) {
                    if (i != parts.length - 1) {
                        return null;
                    }
                    recursive = true;
                } else if (p.contains("**")) {
                    return null;
                } else {
                    segs.add(p);
                }
            }
        }
        if (segs.isEmpty() && !recursive) {
            return null;
        }
        return new TagPattern(provider, segs, recursive);
    }

    public String provider() {
        return provider;
    }

    /** Segments before a trailing {@code **} (all segments when not recursive). */
    public List<String> segments() {
        return segments;
    }

    public boolean isRecursive() {
        return recursive;
    }

    /** True when the pattern names exactly one tag (no wildcard anywhere). */
    public boolean isLiteral() {
        return !recursive && literalPrefixLength() == segments.size();
    }

    /** Number of leading segments that contain no wildcard. */
    public int literalPrefixLength() {
        int n = 0;
        while (n < matchers.size() && matchers.get(n) == null) {
            n++;
        }
        return n;
    }

    /** Number of leading literal segments that are folders (the browse starts below them; never the leaf). */
    public int prefixLength() {
        return Math.max(Math.min(literalPrefixLength(), leafIndex()), 0);
    }

    /** The folder to start browsing from: {@code [provider]} plus the leading literal folder segments. */
    public String prefixPath() {
        return pathOf(segments.subList(0, prefixLength()));
    }

    /** True when {@code depth} is a folder position (a leaf is selected at the following levels). */
    public boolean isFolderPosition(int depth) {
        return recursive || depth < segments.size() - 1;
    }

    /** Full path of the literal tag (only meaningful when {@link #isLiteral()}). */
    public String literalPath() {
        return pathOf(segments);
    }

    /** True when the segment at {@code depth} (0 = just below the provider) is a literal name. */
    public boolean isLiteralAt(int depth) {
        return depth < segments.size() && matchers.get(depth) == null;
    }

    public String segmentAt(int depth) {
        return segments.get(depth);
    }

    /** Depth of the leaf segment; for a recursive pattern leaves lie at depth >= segments.size(). */
    private int leafIndex() {
        return recursive ? segments.size() : segments.size() - 1;
    }

    /** True if a folder named {@code name} found at {@code depth} may be descended into. */
    public boolean matchesFolder(int depth, String name) {
        if (recursive) {
            return depth >= segments.size() || segMatches(depth, name);
        }
        return depth < segments.size() - 1 && segMatches(depth, name);
    }

    /** True if a leaf (atomic tag) named {@code name} found at {@code depth} is selected. */
    public boolean matchesLeaf(int depth, String name) {
        if (recursive) {
            return depth >= segments.size();
        }
        return depth == segments.size() - 1 && segMatches(depth, name);
    }

    /** Whole-path match of a leaf, used for tests and defensive checks. */
    public boolean matches(String path) {
        TagPattern t = parseLiteral(path);
        if (t == null || !t.provider.equalsIgnoreCase(provider)) {
            return false;
        }
        List<String> segs = t.segments;
        for (int i = 0; i < segs.size(); i++) {
            boolean last = i == segs.size() - 1;
            if (last ? matchesLeaf(i, segs.get(i)) : matchesFolder(i, segs.get(i))) {
                continue;
            }
            return false;
        }
        return !segs.isEmpty();
    }

    private static TagPattern parseLiteral(String path) {
        if (path == null || path.contains("*")) {
            return null;
        }
        return parse(path);
    }

    private boolean segMatches(int depth, String name) {
        Pattern p = matchers.get(depth);
        return p == null ? segments.get(depth).equals(name) : p.matcher(name).matches();
    }

    private String pathOf(List<String> segs) {
        return "[" + provider + "]" + String.join("/", segs);
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                if (lit.length() > 0) {
                    sb.append(Pattern.quote(lit.toString()));
                    lit.setLength(0);
                }
                sb.append(".*");
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) {
            sb.append(Pattern.quote(lit.toString()));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return pathOf(segments) + (recursive ? (segments.isEmpty() ? "**" : "/**") : "");
    }
}
