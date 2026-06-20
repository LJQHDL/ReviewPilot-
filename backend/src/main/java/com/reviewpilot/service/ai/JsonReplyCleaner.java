package com.reviewpilot.service.ai;

/**
 * Shared utility for cleaning LLM text responses before JSON parsing.
 * Both the main review flow and the critic flow need the same fence-stripping
 * and JSON-object extraction — centralised here to avoid divergent copies.
 */
public final class JsonReplyCleaner {

    private JsonReplyCleaner() {}

    /** Strip ```json / ``` / ~~~ fences, handling trailing whitespace and text. */
    public static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Handle ~~~json ... ~~~ (some models use tildes)
        if (t.startsWith("~~~")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1).trim();
            int end = t.lastIndexOf("~~~");
            if (end >= 0) t = t.substring(0, end).trim();
        }
        // Handle ```json ... ``` (most common)
        while (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl < 0) { t = t.substring(3).trim(); break; }
            t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
            t = t.trim();
        }
        return t;
    }

    /**
     * Carve out the JSON object body from text that may have leading prose
     * (e.g. "Here is the JSON: {...}") or a trailing comment. Returns the
     * substring from the first '{' to the last '}'; if either is missing or
     * out of order, returns the input unchanged.
     */
    public static String extractJsonObject(String s) {
        if (s == null || s.isEmpty()) return "";
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first < 0 || last <= first) return s;
        return s.substring(first, last + 1);
    }
}
