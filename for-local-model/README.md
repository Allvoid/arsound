# For local model (read first)

Arsound = ReVanced patches for SoundCloud Android. Kotlin patches inject calls; Java extension does the work.

Files here (short on purpose):
- `rules.md` — must/must-not, build and test commands.
- `code-map.md` — package -> purpose -> start file.
- `downloads.md` — track download flow (non-DRM).

Find code with codegraph, always pass `projectPath: "C:/Ar/arsound"`:
- `codegraph_explore "<question or Class method>"` first; `codegraph_search` by name; `codegraph_callers` before changing a method.
- Each Java package has `package-info.java` with entry points and gotchas. Read it before the package.

Paths:
- EXT = `extensions/arsound/src/main/java/app/revanced/extension/soundcloud/`
- PAT = `patches/src/main/kotlin/app/arsound/patches/soundcloud/`
- Tests = `extensions/arsound/src/test/java/`
