package com.ffdecmcp.tools;

import com.ffdecmcp.SwfSessionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.exporters.modes.ScriptExportMode;
import com.jpexs.decompiler.flash.helpers.CodeFormatting;
import com.jpexs.decompiler.flash.helpers.HighlightedTextWriter;
import com.jpexs.decompiler.flash.tags.base.ASMSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only inspection of AVM1 actions, including nested sprite, button and clip actions. */
public class SwfAs2Tools {
    private record Source(String path, ASMSource actions) {}

    @FunctionalInterface
    private interface Operation {
        JsonObject run() throws Exception;
    }

    public JsonObject listScripts(JsonObject args) {
        return respond(() -> {
            int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = integer(args, "limit", 50, 1, 200);
            String filter = string(args, "filter", "");
            List<Source> sources = sources(args).stream().filter(s -> s.path().contains(filter)).toList();
            checkOffset(offset, sources.size());
            int end = offset + Math.min(limit, sources.size() - offset);
            JsonArray scripts = new JsonArray();
            for (Source source : sources.subList(offset, end)) {
                JsonObject item = new JsonObject();
                item.addProperty("script_path", source.path());
                item.addProperty("action_bytes", source.actions().getActionBytes().getLength());
                scripts.add(item);
            }
            JsonObject result = new JsonObject();
            result.addProperty("total", sources.size());
            result.addProperty("offset", offset);
            result.add("scripts", scripts);
            nextOffset(result, end, sources.size());
            return result;
        });
    }

    public JsonObject decompileScript(JsonObject args) {
        return readScript(args, false);
    }

    public JsonObject getBytecode(JsonObject args) {
        return readScript(args, true);
    }

    private JsonObject readScript(JsonObject args, boolean bytecode) {
        return respond(() -> {
            String path = requiredString(args, "script_path");
            int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
            int maxChars = integer(args, "max_chars", 16000, 1, 65536);
            Source selected = sources(args).stream().filter(s -> s.path().equals(path)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown script_path. Select an exact path from list_as2_scripts: " + path));
            String text = render(selected, bytecode);
            checkOffset(offset, text.length());
            int end = offset + Math.min(maxChars, text.length() - offset);
            JsonObject result = new JsonObject();
            result.addProperty("script_path", path);
            result.addProperty("format", bytecode ? "avm1-pcode" : "actionscript2");
            result.addProperty("total_characters", text.length());
            result.addProperty("offset", offset);
            result.addProperty("source", text.substring(offset, end));
            nextOffset(result, end, text.length());
            return result;
        });
    }

    public JsonObject searchSource(JsonObject args) {
        return respond(() -> {
            String query = requiredString(args, "query");
            if (query.length() > 4096) throw new IllegalArgumentException("query must be at most 4096 UTF-16 code units");
            int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
            int scanLimit = integer(args, "scan_limit", 20, 1, 100);
            int limit = integer(args, "limit", 20, 1, 100);
            boolean caseSensitive = bool(args, "case_sensitive", true);
            Pattern pattern = Pattern.compile(Pattern.quote(query), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            List<Source> sources = sources(args);
            checkOffset(offset, sources.size());
            JsonArray matches = new JsonArray();
            JsonArray failures = new JsonArray();
            int end = offset;
            while (end < sources.size() && end - offset < scanLimit && matches.size() < limit) {
                Source selected = sources.get(end++);
                try {
                    String text = render(selected, false);
                    var matcher = pattern.matcher(text);
                    if (matcher.find()) {
                        int snippetStart = Math.max(0, matcher.start() - 80);
                        int snippetEnd = snippetStart + Math.min(240, text.length() - snippetStart);
                        JsonObject match = new JsonObject();
                        match.addProperty("script_path", selected.path());
                        match.addProperty("match_offset", matcher.start());
                        match.addProperty("snippet_offset", snippetStart);
                        match.addProperty("snippet", text.substring(snippetStart, snippetEnd));
                        matches.add(match);
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    JsonObject failure = new JsonObject();
                    failure.addProperty("script_path", selected.path());
                    failure.addProperty("error", e.toString());
                    failures.add(failure);
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("total_scripts", sources.size());
            result.addProperty("offset", offset);
            result.addProperty("scanned", end - offset);
            result.add("matches", matches);
            result.add("failures", failures);
            nextOffset(result, end, sources.size());
            return result;
        });
    }

    private static List<Source> sources(JsonObject args) throws Exception {
        SwfSessionManager manager = SwfSessionManager.getInstance();
        SWF swf = args.has("file_path") ? manager.getSwf(requiredString(args, "file_path")) : manager.getCurrentSwf();
        return swf.getASMs(true).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Source(entry.getKey(), entry.getValue())).toList();
    }

    private static String render(Source source, boolean bytecode) throws InterruptedException {
        ASMSource actions = source.actions();
        var writer = new HighlightedTextWriter(new CodeFormatting(), false);
        var list = actions.getActions();
        if (bytecode) actions.getASMSource(ScriptExportMode.PCODE, writer, list);
        else actions.getActionScriptSource(writer, list);
        return writer.toString();
    }

    private static void nextOffset(JsonObject result, int end, int total) {
        if (end < total) result.addProperty("next_offset", end);
        else result.add("next_offset", JsonNull.INSTANCE);
    }

    private static void checkOffset(int offset, int total) {
        if (offset > total) throw new IllegalArgumentException("offset exceeds the available length " + total);
    }

    private static String requiredString(JsonObject args, String key) {
        String value = string(args, key, null);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " must be a nonempty string");
        return value;
    }

    private static String string(JsonObject args, String key, String fallback) {
        if (!args.has(key)) return fallback;
        if (!(args.get(key) instanceof JsonPrimitive value) || !value.isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        try {
            if (!(args.get(key) instanceof JsonPrimitive value) || !value.isNumber()) throw new ArithmeticException();
            int number = value.getAsBigDecimal().intValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return number;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer from " + min + " to " + max, e);
        }
    }

    private static boolean bool(JsonObject args, String key, boolean fallback) {
        if (!args.has(key)) return fallback;
        if (!(args.get(key) instanceof JsonPrimitive value) || !value.isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }

    private static JsonObject respond(Operation operation) {
        JsonObject result = new JsonObject();
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        try {
            JsonObject payload = operation.run();
            result.addProperty("isError", false);
            result.add("structuredContent", payload);
            content.addProperty("text", payload.toString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            result.addProperty("isError", true);
            content.addProperty("text", "AS2 inspection failed: " + e.getMessage());
        }
        JsonArray contents = new JsonArray();
        contents.add(content);
        result.add("content", contents);
        return result;
    }
}
