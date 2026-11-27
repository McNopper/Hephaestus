# OpenCode Tasks Maven Plugin

## About this document

- **Kind:** `doc` / module README for the Maven plugin module `eclipse/mojo/opencode-tasks`.
- **Read by:** anyone invoking the plugin or hacking on the mojos; referenced from the root
  `AGENTS.md` workflow ("Maven plans, CMake builds").
- **Related:** the store itself lives in the OSGi-free bundle `eclipse/bundles/com.opencode.ide.tasks`
  (`TaskStore`, `Task`, `TaskFileCodec`); the store layout is documented on `TaskStore`.

Two mojos over the Markdown task store (`.harness/tasks/<project>/*.md` + `_meta.json`).
The store classes are **reused from the tasks bundle** — this module adds no store logic of
its own, only validation, fixing and rendering on top of `TaskFileCodec`/`Task`.

> **Maven plans, CMake builds.** These mojos are planning/reporting tools only — they never
> invoke a compiler, CMake, or any build tool. They read and (with `fix`) rewrite task files.

Neither goal has a default phase; invoke them directly — by **full
coordinates** (the short `opencode-tasks:…` prefix form does not resolve out
of the box; B-021 in `../../../ROADMAP.md` tracks making the plugin
invocable in the documented prefix form). See **Invocation (fresh clone)**
below for the one-time local install and the optional plugin-group setup.

## Invocation (fresh clone)

The plugin is not deployed to any remote repository and its group
(`com.opencode.ide`) is not among Maven's default plugin groups, so the bare
short prefix fails out of the box — `No plugin found for prefix
'opencode-tasks' in the current project and in the plugin groups
[org.apache.maven.plugins, org.codehaus.mojo]`.

Install the plugin into the local repository once per clone (the
`bundles/...` modules provide the in-reactor dependency of this module):

```bash
cd eclipse
.\build.ps1 -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.tasks -pl mojo/opencode-tasks -DskipTests install
```

Then invoke by **full coordinates**, which bypasses prefix resolution entirely.
Run it from a module directory (the goals need a Maven project): `-N` executes
the goal once instead of once per reactor module, and the store root is found
by walking up from the basedir:

```bash
.\build.ps1 -N com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:sync
.\build.ps1 -N com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:plan
```

Optionally, add the plugin group to your `~/.m2/settings.xml` so the short
prefix used in the examples below resolves to the locally installed snapshot:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>com.opencode.ide</pluginGroup>
  </pluginGroups>
</settings>
```

(With `mvn` on PATH and a valid `JAVA_HOME`, the same invocations work without
the `.\build.ps1` wrapper.)

## opencode-tasks:sync

Validates every project directory of the store and, with
`-Dopencode.tasks.fix=true`, applies the safe normalizations:

- **Frontmatter lint** — every `*.md` (excluding `.`/`_` prefixed files) parses via
  `TaskFileCodec`; frontmatter id matches the file name stem; `status`/`type`/`priority`
  are valid; `role` is non-blank; `created_at`/`updated_at` are present and parseable;
  the optional V-model `stage` is a valid `VStages` value or absent (an invalid
  stage is an **unfixable** finding — which stage a hand-edited ticket belongs
  to is a human decision);
  the codec round-trips the file with a stable id.
- **Counter consistency** — `_meta.json` `seq[prefix]` is at least the highest numeric
  suffix of existing ticket files; `counter` is at least the highest `S-NNN` sprint id.
- **Line endings** — files must be LF; CRLF is a finding.
- **Cross-checks** — no duplicate ids across files.

Fixes (only with `-Dopencode.tasks.fix=true`): re-encode CRLF/non-canonical ticket files
through the codec, and bump `_meta.json` counters through a Gson `JsonObject` so unknown
keys and their order survive. Unfixable findings always fail the build; fixable findings
fail it only with `-Dopencode.tasks.strict=true` (default: warn). Fixes never delete or
rename ticket files and never write outside the store root.

```bash
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:sync  # validate, warn on fixables
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:sync -Dopencode.tasks.strict=true  # ... and fail on fixables
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:sync -Dopencode.tasks.fix=true
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:sync -Dopencode.tasks.root=C:/path/.harness/tasks
```

The default root is the nearest `.harness/tasks` found walking up from the module
basedir; when none exists and `opencode.tasks.root` is unset, the goal skips with an INFO.

## opencode-tasks:plan

Renders the task board of every project into `board.md` and `board.html` (standalone
page, inline CSS, escaped content) under `-Dopencode.tasks.out`
(default `target/opencode-tasks/`). Both files show a per-project heading and all seven
status columns in canonical order (paused and blocked included) with counts, ticket lines
(`[id] title - role, N points, assignee, ? blocked: reason`) and a totals line (total/done points). The store is only read.

```bash
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:plan  # every project's full board
mvn com.opencode.ide:opencode-tasks:0.4.7-SNAPSHOT:plan -Dopencode.tasks.out=target/board
```

There is no sprint scope and no sprint flag: `PlanMojo` declares exactly three parameters
(`opencode.tasks.root`, `opencode.tasks.out`, `${project.basedir}`) and every ticket of a
project renders (owner model 2026-11-20: seven status columns, no sprint scope).

## Building / testing

Plain Maven module (JUnit 4, `src/test/java`), part of the Tycho reactor:

```bash
cd eclipse
.\build.ps1 -pl bundles/com.opencode.ide.tools -pl bundles/com.opencode.ide.tasks -pl mojo/opencode-tasks clean verify
```

(The `-pl bundles/...` modules provide the in-reactor dependency of this module.)
