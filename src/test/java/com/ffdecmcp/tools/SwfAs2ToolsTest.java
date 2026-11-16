package com.ffdecmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ffdecmcp.FFDecMcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SwfAs2ToolsTest {
    @TempDir Path tempDir;
    private final ToolRegistry tools = new ToolRegistry();
    private final List<Path> opened = new ArrayList<>();

    @AfterEach
    void closeMovies() {
        for (Path path : opened) {
            tools.callTool("close_swf", args("file_path", path.toString()));
        }
    }

    @Test
    void enumeratesAndDecompilesAs2InGfx() throws Exception {
        Path path = open("synthetic.gfx", movie(true, as2Tags()));
        byte[] before = Files.readAllBytes(path);
        JsonObject listing = data(call("list_as2_scripts", args()));
        assertTrue(listing.get("total").getAsInt() >= 5, listing.toString());
        StringBuilder source = new StringBuilder();
        for (var item : listing.getAsJsonArray("scripts")) {
            source.append(data(call("decompile_as2_script", args("script_path", item.getAsJsonObject().get("script_path").getAsString()))).get("source").getAsString());
        }
        for (String marker : List.of("ROOT_AS2", "NESTED_AS2", "INIT_AS2", "BUTTON_AS2", "CLIP_AS2")) {
            assertTrue(source.toString().contains(marker), "Missing " + marker + ": " + source);
        }
        assertArrayEquals(before, Files.readAllBytes(path));
        assertTrue(data(call("list_scripts", args())).getAsJsonArray("scripts").isEmpty());
    }

    @Test
    void listsEverySourceOnceAcrossPages() throws Exception {
        open("pages.swf", movie(false, as2Tags()));
        JsonObject all = data(call("list_as2_scripts", args()));
        List<String> paths = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonObject page = data(call("list_as2_scripts", args("offset", offset, "limit", 1)));
            assertEquals(1, page.getAsJsonArray("scripts").size());
            paths.add(page.getAsJsonArray("scripts").get(0).getAsJsonObject().get("script_path").getAsString());
            if (page.get("next_offset").isJsonNull()) break;
            offset = page.get("next_offset").getAsInt();
        }
        assertEquals(all.get("total").getAsInt(), paths.size());
        assertEquals(paths.size(), new HashSet<>(paths).size());
        assertEquals(paths.stream().sorted().toList(), paths);
        JsonObject filtered = data(call("list_as2_scripts", args("filter", paths.get(0))));
        assertTrue(filtered.getAsJsonArray("scripts").size() > 0);
    }

    @Test
    void decompilesAndDisassemblesSelectedSourcesWithPaging() throws Exception {
        open("source.swf", movie(false, as2Tags()));
        String path = firstSource();
        String full = data(call("decompile_as2_script", args("script_path", path))).get("source").getAsString();
        StringBuilder joined = new StringBuilder();
        int offset = 0;
        while (true) {
            JsonObject page = data(call("decompile_as2_script", args("script_path", path, "offset", offset, "max_chars", 7)));
            assertTrue(page.get("source").getAsString().length() <= 7);
            joined.append(page.get("source").getAsString());
            if (page.get("next_offset").isJsonNull()) break;
            offset = page.get("next_offset").getAsInt();
        }
        assertEquals(full, joined.toString());
        String pcode = data(call("get_as2_bytecode", args("script_path", path))).get("source").getAsString();
        assertTrue(pcode.toLowerCase().contains("trace"), pcode);
    }

    @Test
    void searchesAs2AcrossBoundedPagesAndTreatsQueriesLiterally() throws Exception {
        open("search.swf", movie(false, as2Tags()));
        int offset = 0;
        int matches = 0;
        int scanned = 0;
        do {
            JsonObject page = data(call("search_as2_source", args("query", "ROOT_AS2", "offset", offset, "scan_limit", 1)));
            scanned += page.get("scanned").getAsInt();
            matches += page.getAsJsonArray("matches").size();
            assertTrue(page.getAsJsonArray("failures").isEmpty());
            if (page.get("next_offset").isJsonNull()) break;
            offset = page.get("next_offset").getAsInt();
        } while (true);
        assertEquals(1, matches);
        assertEquals(data(call("list_as2_scripts", args())).get("total").getAsInt(), scanned);
        JsonObject literal = data(call("search_as2_source", args("query", "ROOT.*AS2", "scan_limit", 100)));
        assertTrue(literal.getAsJsonArray("matches").isEmpty());
    }

    @Test
    void reportsUnknownPathsAndRejectsInvalidArguments() throws Exception {
        open("invalid.swf", movie(false, as2Tags()));
        JsonObject missing = tools.callTool("decompile_as2_script", args("script_path", "/not-present"));
        assertTrue(missing.get("isError").getAsBoolean());
        assertTrue(missing.toString().contains("script_path"));
        for (JsonObject invalid : List.of(args("offset", -1), args("offset", 0.5), args("limit", 0), args("limit", 201))) {
            assertTrue(tools.callTool("list_as2_scripts", invalid).get("isError").getAsBoolean(), invalid.toString());
        }
        assertTrue(tools.callTool("search_as2_source", args("query", "")).get("isError").getAsBoolean());
        assertTrue(tools.callTool("decompile_as2_script", args()).get("isError").getAsBoolean());
    }

    @Test
    void distinguishesMoviesWithNoAs2AndPreservesAs3Tools() throws Exception {
        open("as3.swf", movie(false, tag(69, new byte[]{8, 0, 0, 0}), tag(82, concat(new byte[5], minimalAbc()))));
        assertEquals(0, data(call("list_as2_scripts", args())).get("total").getAsInt());
        JsonObject classes = call("list_classes", args());
        assertEquals(1, classes.getAsJsonArray("classes").size(), classes.toString());
        JsonObject scripts = call("list_scripts", args());
        assertEquals(1, scripts.getAsJsonArray("scripts").size());
        String source = call("decompile_script", args("script_index", 0)).get("source").getAsString();
        assertTrue(source.contains("As3Smoke"), source);
    }

    @Test
    void reloadUsesNewActionsInsteadOfStaleSource() throws Exception {
        Path file = open("reload.swf", movie(false, tag(12, trace("BEFORE_AS2"))));
        String path = firstSource();
        assertTrue(data(call("decompile_as2_script", args("script_path", path))).get("source").getAsString().contains("BEFORE_AS2"));
        Files.write(file, movie(false, tag(12, trace("AFTER_AS2"))));
        call("reload_swf", args("file_path", file.toString()));
        String after = data(call("decompile_as2_script", args("script_path", firstSource()))).get("source").getAsString();
        assertTrue(after.contains("AFTER_AS2"), after);
        assertFalse(after.contains("BEFORE_AS2"), after);
    }

    @Test
    void preservesPaginationAndShutdownNullsInJsonRpcResponses() throws Exception {
        Path path = open("transport.swf", movie(false, as2Tags()));
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", "list_as2_scripts");
        params.add("arguments", args("file_path", path.toString()));
        request.add("params", params);
        String input = request + "\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FFDecMcpServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output).run();
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n");
        JsonObject result = JsonParser.parseString(lines[0]).getAsJsonObject().getAsJsonObject("result").getAsJsonObject("structuredContent");
        assertTrue(result.has("next_offset"), result.toString());
        assertTrue(result.get("next_offset").isJsonNull());
        assertTrue(JsonParser.parseString(lines[1]).getAsJsonObject().get("result").isJsonNull());
    }

    @Test
    void processExitsAfterAs2DecompilationAndShutdown() throws Exception {
        assertProcessExits(true);
    }

    @Test
    void processExitsAfterAs2DecompilationAndEof() throws Exception {
        assertProcessExits(false);
    }

    private void assertProcessExits(boolean shutdown) throws Exception {
        Path path = open("process.swf", movie(false, as2Tags()));
        Path stdout = tempDir.resolve("process.stdout");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), "com.ffdecmcp.FFDecMcpServer")
                .redirectOutput(stdout.toFile()).redirectError(tempDir.resolve("process.stderr").toFile()).start();
        JsonObject parameters = new JsonObject();
        parameters.addProperty("name", "decompile_as2_script");
        parameters.add("arguments", args("file_path", path.toString(), "script_path", firstSource()));
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "tools/call");
        request.add("params", parameters);
        try {
            String input = request + "\n" + (shutdown ? "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}\n" : "");
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "JPEXS workers kept the MCP process alive after its request loop ended");
            assertEquals(0, process.exitValue());
            String output = Files.readString(stdout);
            assertTrue(output.contains("actionscript2"), output);
            assertFalse(output.contains("\"isError\":true"), output);
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Path open(String name, byte[] bytes) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, bytes);
        opened.add(path);
        call("open_swf", args("file_path", path.toString()));
        return path;
    }

    private String firstSource() {
        return data(call("list_as2_scripts", args())).getAsJsonArray("scripts").get(0).getAsJsonObject().get("script_path").getAsString();
    }

    private JsonObject call(String name, JsonObject arguments) {
        JsonObject result = tools.callTool(name, arguments);
        assertFalse(result.get("isError").getAsBoolean(), name + ": " + result);
        return result;
    }

    private static JsonObject data(JsonObject result) {
        return result.has("structuredContent") ? result.getAsJsonObject("structuredContent") : result;
    }

    private static JsonObject args(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i+1];
            if (value instanceof Number number) result.addProperty(key, number);
            else result.addProperty(key, (String) value);
        }
        return result;
    }

    private static byte[][] as2Tags() {
        byte[] nested = tag(39, concat(le(7, 2), le(1, 2), tag(12, trace("NESTED_AS2")), tag(1, new byte[0]), tag(0, new byte[0])));
        byte[] button = tag(34, concat(le(11, 2), new byte[]{0}, le(3, 2), new byte[]{0}, le(0, 2), le(8, 2), trace("BUTTON_AS2")));
        byte[] clip = tag(26, concat(new byte[]{(byte) 0x82}, le(1, 2), le(7, 2), le(0, 2), le(128, 4), le(128, 4), le(trace("CLIP_AS2").length, 4), trace("CLIP_AS2"), le(0, 4)));
        return new byte[][]{nested, button, tag(59, concat(le(7, 2), trace("INIT_AS2"))), clip, tag(12, trace("ROOT_AS2"))};
    }

    private static byte[] trace(String text) {
        byte[] push = concat(new byte[]{0}, text.getBytes(StandardCharsets.UTF_8), new byte[]{0});
        return concat(new byte[]{(byte) 0x96}, le(push.length, 2), push, new byte[]{0x26, 0});
    }

    private static byte[] movie(boolean gfx, byte[]... tags) throws Exception {
        byte[] body = concat(new byte[]{8, 0, 0, 24, 1, 0}, concat(tags), tag(1, new byte[0]), tag(0, new byte[0]));
        if (!gfx) return concat("FWS".getBytes(StandardCharsets.US_ASCII), new byte[]{9}, le(body.length+8, 4), body);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed)) { stream.write(body); }
        return concat("CFX".getBytes(StandardCharsets.US_ASCII), new byte[]{9}, le(body.length+8, 4), compressed.toByteArray());
    }

    private static byte[] tag(int id, byte[] bytes) {
        return bytes.length < 63 ? concat(le(id << 6 | bytes.length, 2), bytes) : concat(le(id << 6 | 63, 2), le(bytes.length, 4), bytes);
    }

    private static byte[] minimalAbc() {
        // Authored ABC: an empty As3Smoke extends Object with one script class trait.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{16, 0, 46, 0, 1, 1, 1, 4});
        for (String text : List.of("", "Object", "As3Smoke")) {
            out.write(text.length());
            out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(new byte[]{2, 0x16, 1, 1, 3, 7, 1, 2, 7, 1, 3, 3});
        out.writeBytes(new byte[12]); // Three methods: no parameters, return type or name.
        out.writeBytes(new byte[]{0, 1, 2, 1, 0, 0, 0, 0, 1, 0, 1, 2, 1, 2, 4, 1, 0, 3});
        for (int i = 0; i < 3; i++) {
            byte[] code = i == 0 ? new byte[]{(byte) 0xd0, 0x30, (byte) 0xd0, 0x49, 0, 0x47} : new byte[]{(byte) 0xd0, 0x30, 0x47};
            out.writeBytes(new byte[]{(byte) i, 1, 1, 0, 1, (byte) code.length});
            out.writeBytes(code);
            out.writeBytes(new byte[]{0, 0});
        }
        return out.toByteArray();
    }

    private static byte[] le(int value, int count) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) bytes[i] = (byte) (value >>> (8*i));
        return bytes;
    }

    private static byte[] concat(byte[]... arrays) {
        byte[] result = new byte[Arrays.stream(arrays).mapToInt(a -> a.length).sum()];
        int offset = 0;
        for (byte[] array : arrays) { System.arraycopy(array, 0, result, offset, array.length); offset += array.length; }
        return result;
    }
}
