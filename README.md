# FFDec MCP Server

[![AS2 tests](https://github.com/aivv73/ffdec-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/aivv73/ffdec-mcp/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for analyzing and manipulating Shockwave Flash (SWF) files using [JPEXS Free Flash Decompiler (FFDec)](https://github.com/jindrapetrik/jpexs-decompiler).

This fork adds read-only ActionScript 2 and Scaleform GFx inspection for Mudcrab development to [Xeleron's FFDec MCP server](https://github.com/Xeleron/ffdec-mcp).
It keeps the existing AS3 tools and adds source enumeration, paged decompilation, AVM1 P-code, and source search.
The repository contains no Skyrim assets or decompiled game scripts.

---

## Quick Start

### Build and register with Codex

```bash
git clone https://github.com/aivv73/ffdec-mcp.git
cd ffdec-mcp
bash scripts/build.sh
codex mcp add ffdec -- java -Djava.awt.headless=true -jar "$PWD/target/ffdec-mcp-server-1.0.0.jar"
```

The build requires Java 25, Maven, curl, and unzip. It downloads FFDec library 25.1.3, verifies its SHA-256, and runs ten synthetic tests.
It selects `SwfAs2ToolsTest`; older upstream tests require an external `original_spider.swf` fixture.
Open or refresh your Codex session after registration. Other MCP clients can launch the same `java` command over stdio.

---

## Features

| Category | Capabilities |
| :--- | :--- |
| **Analysis** | Read SWF headers, list tags/classes/symbols, inspect bytecode |
| **Decompilation** | Extract ActionScript 3 source from classes and scripts |
| **ActionScript 2** | List nested AVM1 sources, decompile, disassemble, and search SWF and Scaleform GFx movies |
| **Modification** | Patch classes/methods, edit constants, rename identifiers |
| **Injection** | Add new classes, methods, fields, or import ABC from files |
| **Export** | Save modified SWFs, export specific components |

---

## Prerequisites

| Requirement | Version | Notes |
| :--- | :--- | :--- |
| **Java** | 25+ | Required for local builds (JDK, not JRE) |
| **Maven** | 3.6+ | For building from source |
| **Podman/Docker** | Any | Recommended alternative to local Java setup |

---

## Installation

### Option 1: Podman/Docker (Best for stability)

The Dockerfile handles all FFDec library dependencies automatically.

```bash
podman build -t ffdec-mcp-server:latest .
```

Add this to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "ffdec": {
      "command": "podman",
      "args": [
        "run",
        "-i",
        "--rm",
        "-v",
        "/absolute/path/to/your/swfs:/swfs:rw",
        "ffdec-mcp-server:latest"
      ]
    }
  }
}
```

> **Note**: Replace `/absolute/path/to/your/swfs` with your actual local directory.

### Option 2: Local build

Use `bash scripts/build.sh` from the quick start. The output is `target/ffdec-mcp-server-1.0.0.jar`.
For a previously downloaded library archive, set `FFDEC_LIB_ARCHIVE` to its absolute path. The script verifies the same checksum.
Maven arguments can follow the script name, for example `bash scripts/build.sh --batch-mode`.
The shell launcher is `scripts/ffdec-mcp`; Windows clients can use `scripts/ffdec-mcp.bat` after building the JAR.

---

## Available Tools

The server exposes 40+ tools. Use `tools/list` at runtime for full technical definitions.

<details>
<summary><strong> Session Management</strong></summary>

| Tool | Description |
| :--- | :--- |
| `open_swf` | Open a SWF file and create a session |
| `close_swf` | Close a session and free resources |
| `reload_swf` | Reload from disk, discarding unsaved changes |
| `list_sessions` | List all open SWF sessions |
| `save_swf` | Save modifications to disk |

</details>

<details>
<summary><strong> Analysis & Inspection</strong></summary>

| Tool | Description |
| :--- | :--- |
| `get_swf_info` | SWF header and metadata (version, size, dimensions) |
| `list_classes` | Enumerate AS3 classes and scripts |
| `list_tags` | List all tags and their types |
| `list_symbols` | SymbolClass associations (ID ↔ class name) |
| `list_scripts` | AS3 script packs and indices |
| `list_methods` | Instance and static methods for a class |
| `get_traits` | All traits (methods, fields, accessors) for a class |
| `get_class_hierarchy` | Superclass and interfaces |
| `get_dependencies` | Classes referenced by a given class |
| `get_constant_pool` | Constant pool snippets (strings, ints) |
| `get_tag_details` | Detailed metadata for a tag by index |

</details>

<details>
<summary><strong> Decompilation & Search</strong></summary>

| Tool | Description |
| :--- | :--- |
| `decompile_class` | Decompile AS3 class to source |
| `decompile_script` | Decompile script by index |
| `get_bytecode` | AVM2 bytecode (P-code) for method/class |
| `search_source` | Search decompiled AS3 source |
| `search_pcode` | Search bytecode for patterns |
| `find_class` | Find classes matching a query |
| `find_methods` | Find methods across scripts/classes |
| `resolve_identifier` | Resolve name to internal indices |

</details>

<details>
<summary><strong> Modification</strong></summary>

| Tool | Description |
| :--- | :--- |
| `patch_class` | Replace entire class source and recompile |
| `patch_method` | Replace a single method's source |
| `patch_bytecode` | Replace method P-code directly |
| `edit_constants` | Find-and-replace constants in ABC pool |
| `rename_identifier` | Rename class/method/field consistently |
| `modify_symbol` | Add/remove/change SymbolClass associations |
| `modify_swf_header` | Change version, frame rate, dimensions |
| `modify_class_flags` | Toggle sealed/final/interface flags |
| `replace_superclass` | Change a class's superclass |

</details>

<details>
<summary><strong> Injection & Removal</strong></summary>

| Tool | Description |
| :--- | :--- |
| `inject_class` | Add new AS3 class (creates DoABC tag) |
| `inject_abc_from_file` | Import ABC tags from SWF/.abc file |
| `add_method` | Append method to existing class |
| `add_field` | Add field to existing class |
| `add_interface` | Add interface implementation |
| `remove_trait` | Remove method/property trait |
| `remove_tag` | Remove tag by index |
| `delete_class` | Remove class from ABC data |

</details>

---

## ActionScript 2 and Scaleform GFx

Local verification opened one retail Skyrim `hudmenu.gfx` and seven UI SWFs, enumerated 1,892 AS2 sources, and checked decompilation, P-code, and search on each movie.
Initial samples matched direct JPEXS output. JPEXS can infer different declarations between sessions; the verifier checks page consistency using two page sizes within the same session.
These checks establish inspection support, not rendering or behavioral parity.
The retail inputs and results stay outside Git. CI uses authored synthetic fixtures and requires no game installation.

These read-only tools inspect AS2 actions in frames, initialization scripts, nested sprites, buttons, and clip events:

| Tool | Result |
| :--- | :--- |
| `list_as2_scripts` | Sorted source paths, action byte lengths, and a page of up to 200 entries |
| `decompile_as2_script` | A page of decompiled AS2 source |
| `get_as2_bytecode` | A page of AVM1 P-code |
| `search_as2_source` | One snippet per matching source and explicit decompilation failures |

Each tool accepts `file_path`. Omit it to use the active movie. Select `script_path` exactly as returned by `list_as2_scripts`.

List and search responses include `next_offset`. Pass that offset to the next request until it is `null`.
For text responses, `offset` and `max_chars` count UTF-16 code units. The default page contains up to 16,000 code units.
Search queries are literal and case-sensitive by default. Each request scans up to 20 sources unless `scan_limit` is supplied.
An empty search page does not establish absence when `next_offset` is non-null or `failures` is nonempty.

Results are available in `structuredContent` and as JSON in the text content block. The existing AS3 tools keep their AS3 behavior.
AS2 editing and recompilation are not provided by these tools.

### Verify AS2 support

Run the synthetic tests, which create their own SWF, compressed GFx, and AS3 fixtures:

```bash
bash scripts/build.sh
```

To check movies from your own installation through the packaged server, supply a new output directory:

```bash
python3 scripts/verify_as2_movies.py \
	--jar target/ffdec-mcp-server-1.0.0.jar \
	--output /private/fresh-as2-results \
	/path/to/menu.gfx /path/to/menu.swf
```

The verifier checks source listing, paged decompilation, P-code, search, input hashes, and process shutdown.
It saves decompiled content and protocol transcripts in the output directory. Keep that directory outside the repository.

## Example Workflows

### Analyzing a SWF

> **User**: "Open game.swf and show me what classes it contains"
> **AI**: Uses `open_swf`, then `list_classes`.

### Modifying a SWF

> **User**: "Change the player's max health from 100 to 200 in the Player class"
> **AI**: Searches for the constant, uses `edit_constants` or `patch_method`, then `save_swf`.

---

## Troubleshooting

<details>
<summary><strong>Build fails with "ffdec_lib not found"</strong></summary>

The FFDec library is not in Maven Central. Use the **Docker build** to handle this automatically, or manually install the JAR using `mvn install:install-file`.

</details>

<details>
<summary><strong>Server won't start: "Java version X required"</strong></summary>

Run `java -version`. This project requires **Java 25+**.

</details>

---

## License

Licensed under the **Apache License 2.0**.

### Third-Party Licenses

| Component | License | Notes |
| :--- | :--- | :--- |
| `ffdec_lib` | [LGPLv3](https://www.gnu.org/licenses/lgpl-3.0.html) | Core SWF analysis library |
