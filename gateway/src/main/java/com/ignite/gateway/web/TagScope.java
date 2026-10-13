package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Per-site tag allow-list (ADR-004 Amendment A1). Blank patterns mean unrestricted.
 *
 * Pattern grammar (one per line or comma-separated): {@code [provider]path} with an optional
 * trailing {@code /*} (direct children only) or {@code /**} (the folder and everything below).
 * Provider names compare case-insensitively, path segments exactly. The core works on path
 * strings so it is testable without the Ignition runtime; TagPath overloads delegate to it.
 */
public final class TagScope {

    public static final TagScope UNRESTRICTED = new TagScope(null);

    private enum Mode { EXACT, CHILDREN, RECURSIVE }

    private static final class Pattern {
        final String provider;
        final List<String> base;
        final Mode mode;

        Pattern(String provider, List<String> base, Mode mode) {
            this.provider = provider;
            this.base = base;
            this.mode = mode;
        }
    }

    /** null = unrestricted. */
    private final List<Pattern> patterns;

    private TagScope(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /** Builds a scope from the stored field; blank is unrestricted, malformed lines are ignored (deny). */
    public static TagScope of(String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return UNRESTRICTED;
        }
        List<Pattern> out = new ArrayList<>();
        for (String line : split(patterns)) {
            Pattern p = parsePattern(line);
            if (p != null) {
                out.add(p);
            }
        }
        return new TagScope(out);
    }

    public static TagScope forSite(SiteRecord site) {
        return site == null ? of("[]") : of(site.getTagPathPatterns());
    }

    /** Returns the first malformed non-blank pattern, or null when all are valid. */
    public static String firstInvalid(String patterns) {
        if (patterns == null) {
            return null;
        }
        for (String line : split(patterns)) {
            if (parsePattern(line) == null) {
                return line;
            }
        }
        return null;
    }

    public boolean isUnrestricted() {
        return patterns == null;
    }

    public boolean allows(TagPath path) {
        return allows(path == null ? null : path.toString());
    }

    public boolean allows(String path) {
        if (patterns == null) {
            return true;
        }
        Parsed t = parsePath(path);
        if (t == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (matches(p, t)) {
                return true;
            }
        }
        return false;
    }

    /** True if the folder is allowed, or any pattern lies at or below it (so a browse can proceed and be filtered). */
    public boolean allowsFolderBrowse(TagPath folder) {
        return allowsFolderBrowse(folder == null ? null : folder.toString());
    }

    public boolean allowsFolderBrowse(String folder) {
        if (patterns == null) {
            return true;
        }
        Parsed f = parsePath(folder);
        if (f == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (matches(p, f)) {
                return true;
            }
            if (p.provider.equalsIgnoreCase(f.provider) && startsWith(p.base, f.segments)) {
                return true;
            }
        }
        return false;
    }

    /** Browse of the tag root ("" ): allowed when any pattern exists. */
    public boolean allowsRootBrowse() {
        return patterns == null || !patterns.isEmpty();
    }

    /** True if the provider (browse root node, e.g. "[default]") is named by any pattern. */
    public boolean allowsProvider(String providerName) {
        if (patterns == null) {
            return true;
        }
        for (Pattern p : patterns) {
            if (p.provider.equalsIgnoreCase(providerName)) {
                return true;
            }
        }
        return false;
    }

    public List<TagPath> filter(List<TagPath> paths) {
        if (patterns == null) {
            return paths;
        }
        List<TagPath> out = new ArrayList<>();
        for (TagPath p : paths) {
            if (allows(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Alarm sources
    // -------------------------------------------------------------------------

    /**
     * Converts an alarm event source such as {@code prov:default:/tag:Folder/Tag} (an optional
     * {@code :/alm:Name} suffix is dropped) to tag path form {@code [default]Folder/Tag}.
     * Returns null for anything that is not a tag source.
     */
    public static String alarmSourceToTagPath(String source) {
        if (source == null || !source.startsWith("prov:")) {
            return null;
        }
        int provEnd = source.indexOf(':', 5);
        if (provEnd < 6) {
            return null;
        }
        String provider = source.substring(5, provEnd);
        String rest = source.substring(provEnd + 1);
        if (!rest.startsWith("/tag:")) {
            return null;
        }
        rest = rest.substring(5);
        int alm = rest.indexOf(":/alm:");
        if (alm >= 0) {
            rest = rest.substring(0, alm);
        }
        return "[" + provider + "]" + rest;
    }

    /** True if the alarm source is a tag source inside the scope. */
    public boolean allowsAlarmSource(String source) {
        if (patterns == null) {
            return true;
        }
        return allows(alarmSourceToTagPath(source));
    }

    // -------------------------------------------------------------------------
    // Denial helper
    // -------------------------------------------------------------------------

    /** Sets 403, logs one DEBUG line (site, path) and returns {"error":"tag path not permitted"}. */
    public static JsonObject deny(HttpServletResponse res, LoggerEx log, SiteRecord site, Object path) {
        log.debug("tag path not permitted: site=" + (site != null ? site.getName() : null) + " path=" + path);
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return AuthUtil.errorJson("tag path not permitted");
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static final class Parsed {
        final String provider;
        final List<String> segments;

        Parsed(String provider, List<String> segments) {
            this.provider = provider;
            this.segments = segments;
        }
    }

    private static List<String> split(String patterns) {
        List<String> out = new ArrayList<>();
        for (String s : patterns.split("[\\r\\n,]")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** {@code [provider]a/b/c} to provider + segments; null if malformed. "." and ".." segments are rejected. */
    private static Parsed parsePath(String path) {
        if (path == null || !path.startsWith("[")) {
            return null;
        }
        int close = path.indexOf(']');
        if (close < 2) {
            return null;
        }
        String provider = path.substring(1, close);
        String rest = path.substring(close + 1);
        List<String> segs = new ArrayList<>();
        if (!rest.isEmpty()) {
            for (String s : rest.split("/", -1)) {
                if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                    return null;
                }
                segs.add(s);
            }
        }
        return new Parsed(provider, segs);
    }

    private static Pattern parsePattern(String line) {
        String s = line.trim();
        Mode mode = Mode.EXACT;
        if (s.endsWith("/**")) {
            mode = Mode.RECURSIVE;
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("/*")) {
            mode = Mode.CHILDREN;
            s = s.substring(0, s.length() - 2);
        }
        if (s.indexOf('*') >= 0) {
            return null;
        }
        Parsed p = parsePath(s);
        if (p == null) {
            return null;
        }
        if (mode == Mode.EXACT && p.segments.isEmpty()) {
            return null;
        }
        return new Pattern(p.provider, Collections.unmodifiableList(p.segments), mode);
    }

    private static boolean matches(Pattern p, Parsed t) {
        if (!p.provider.equalsIgnoreCase(t.provider)) {
            return false;
        }
        switch (p.mode) {
            case EXACT:
                return p.base.equals(t.segments);
            case CHILDREN:
                return t.segments.size() == p.base.size() + 1 && startsWith(t.segments, p.base);
            default:
                return startsWith(t.segments, p.base);
        }
    }

    /** True if {@code list} begins with all of {@code prefix}. */
    private static boolean startsWith(List<String> list, List<String> prefix) {
        return list.size() >= prefix.size() && list.subList(0, prefix.size()).equals(prefix);
    }

    @Override
    public String toString() {
        return patterns == null ? "TagScope[unrestricted]" : "TagScope" + Arrays.toString(patterns.toArray());
    }
}
