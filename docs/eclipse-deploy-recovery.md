# Eclipse deploy and install recovery

## About this document
- **Kind:** `doc` / operational runbook for deploying the plugin jars into a
  running Eclipse CDT install and for recovering a damaged install.
- **Read by:** anyone deploying or testing the plugin on a real Eclipse;
  **written by:** maintainers.
- **Related:** `eclipse/deploy-dev.ps1` (the tool this documents),
  `../ROADMAP.md`, `HISTORY.md` (why these behaviors exist).

## Deploying

```powershell
cd eclipse
.\build.ps1 verify          # or clean verify
.\deploy-dev.ps1            # [-EclipseRoot C:\eclipse-cpp]
```

`deploy-dev.ps1` wipes the `dropins\opencode-ide` folder and copies the fresh
jars, then:

- clears `configuration\org.eclipse.osgi` (the OSGi bundle cache). This is the
  `-clean` equivalent: without it, p2 sees the previous build's qualifiers and
  reports "another singleton bundle selected" conflicts. **Never delete
  `org.eclipse.equinox.simpleconfigurator\bundles.info` instead** — that file
  is the bootstrap list of bundles to start; without it the framework cannot
  even launch the code that would rebuild it, and the install only recovers by
  renaming away the whole `configuration` directory.
- if `bundles.info` already carries manual `file:dropins/opencode-ide/...`
  lines (the recovery state below), refreshes them to the freshly built
  versions so OSGi never sees a manifest/bundles.info version mismatch.
  Normal installs have no such lines and rely on the p2 dropins reconciler.
- copies `plugin_customization.ini` to the Eclipse root and, if the flag is
  absent, inserts `-pluginCustomization plugin_customization.ini` into
  `eclipse.ini` (just before `-vmargs`) — the seeded defaults for connection
  scope and task store.
- pins `-Dopencode.repo=<repo-of-the-deploy>` as a vmargs line in
  `eclipse.ini` (idempotent — only added when missing). This is the
  authoritative "where is my project" setting: connection scope and task
  store both resolve from it, and **when set it wins over every preference
  and ini key**, including the `plugin_customization.ini` values above.

**Launch with an explicit workspace** when scripting (`-data
C:\Development\workspace-cpp`); without it the instance data location can stay
unset in some launcher flows and every preferences-touching bundle degrades.

**Do not launch while a deploy is running** — a start racing the cache wipe
dies with "Unable to acquire application service". The first start after a
deploy is slower (cache rebuild + p2 reconciliation); let it finish.

## Install recovery (damaged p2 profile)

Symptoms: instant "Unable to acquire application service" exits,
`bundles.info` missing or stripped of exploded-directory bundles, resolution
cascades ending in
`Application "org.eclipse.ui.ide.workbench" could not be found` (the damaged
state below).

Recovery (in order):

1. **Restore `configuration\config.ini`.** A correct one boots
   `org.eclipse.equinox.simpleconfigurator` via `osgi.bundles` with an
   **absolute, escaped** URL:
   `osgi.bundles=reference\:file\:C\:/eclipse-cpp/plugins/org.eclipse.equinox.simpleconfigurator_<ver>.jar@1\:start`
   plus `osgi.bundles.defaultStartLevel=4`, `osgi.framework`, and the
   `eclipse.*` product keys. (Relative `reference:file:` entries resolve
   against an area where the jar is not — that form is what a broken
   reconciliation had written.)
2. **Regenerate `bundles.info` from the actual `plugins/` content.** Two kinds
   of entries exist and both must be present:
   - every `*.jar`: `<id>,<version>,file:plugins/<name>.jar,<level>,<started>`
   - every **exploded directory** with `META-INF/MANIFEST.MF` (an EPP install
     carries several — product bundle, splash, win32 fragments, the JustJ
     JRE) — a jar-only scan silently drops them.
   Read id/version from each manifest. Safe flags: `org.eclipse.equinox.
   simpleconfigurator` → `1,true`; `org.eclipse.equinox.common` → `2,true`;
   `org.eclipse.core.runtime` → `4,true`; the runtime-critical set
   (`org.eclipse.core.contenttype`, `.jobs`, `.filesystem`,
   `equinox.preferences`, `.registry`, `.app`, `.event`,
   `org.apache.felix.scr`) → `4,true`; everything else `4,false` (lazy
   activation). Without `contenttype` auto-started, `Platform.
   getContentTypeManager()` is null and `org.eclipse.core.resources` dies
   during IDEApplication startup.
3. **Add the opencode-ide dropin jars as explicit lines**
   (`file:dropins/opencode-ide/plugins/<jar>.jar`) if the dropins reconciler
   no longer manages them — a damaged profile stops doing so. Deploy-
   dev.ps1 keeps these lines current on every redeploy.
4. Launch once; expect one p2-triggered framework restart while
   reconciliation settles, then a normal workbench.

**Caveat:** the recovered install runs on hand-maintained `bundles.info` lines
for the opencode bundles. A clean reinstall of the Eclipse CDT package (or a
p2 profile repair via the installer) removes that crutch; the plugins
themselves are ordinary dropins again afterwards.

## Developing the plugin inside Eclipse

The install carries m2e plus the m2e-PDE integration (Tycho/OSGi support), and
the bundle directories carry PDE metadata (`.project`, `.classpath`,
`.settings/`) — either import style works in ONE workspace:

- *File → Import → Maven → Existing Maven Projects* → root `eclipse/pom.xml`
  → import all (preferred; m2e-PDE maps the Tycho packaging to PDE natures and
  resolves workspace OSGi dependencies).
- *File → Import → General → Existing Projects into Workspace* → root
  `eclipse/bundles` (plain PDE import; dependencies resolve against the
  running Eclipse, which contains every bundle via the dropins).

Eclipse's command-line `-import` flag proved unreliable for this (silently
no-ops on a workspace with existing UI state; the batch import only took on a
freshly initialized workspace). Edit; verify with a nested workbench
(*Run → Run Configurations → Eclipse Application* — workspace bundles override
the deployed dropins in the child) or per-bundle JUnit Plug-in tests. Real
builds and deploys still go through `build.ps1 verify` + `deploy-dev.ps1`
(close Eclipse before deploying — the jars are locked while it runs).

EGit covers commit/push from inside Eclipse (*Team → Commit/Push*); the HOME
warning on startup is cosmetic (Git for Windows and EGit agree on
`C:\Users\<user>` for global config). A repo-root `.project` still conflicts
with the nested bundle projects (Eclipse forbids overlapping projects), which
is why THIS repo's dev layout keeps working through markers. For adopting
other repos, O-001 is done: the OpenCode repo nature plus the Board's
*Adopt repo…* action — it writes the root `.project`, imports and opens the
repo as a workspace project, and refuses nested-project repos with the
auto-discovery explanation.

## Known launch-time pitfalls fixed in the plugin

| Pitfall | Fix |
|---|---|
| Bundle activator touched `InstanceScope` before the instance area existed → core bundle dead, every view "Could not create" | Singletons constructed lazily behind OSGi service factories; preferences materialize on first real use |
| Eager `ProjectContext` tracker during `start()` re-entered the CDT component's creation → Felix SCR "circular reference", CDT integration disabled | Tracker opens lazily on first `getProjectContext()` |
| Spawned server and client generated **two different** random passwords → every request HTTP 401 | One password per launcher lifecycle, reused for the client config |
| Stale orphaned `opencode serve` on a **configured** port → endless 401s | `requirePortFree` fails fast with a message naming the stale server |
