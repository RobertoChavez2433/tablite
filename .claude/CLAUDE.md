# Runelite for Tablet

Tablet-optimized OSRS client based on RuneLite. Android app orchestrates Termux, proot, X11, and shell scripts to run RuneLite on tablets.

## Build

```bash
cd runelite-tablet && ./gradlew build
```

Source root: `runelite-tablet/app/src/main/java/com/runelitetablet/`

## Working Rules

- **DI**: Manual constructor injection, wired in Application/Activity
- **Navigation**: Single-screen, state-driven content switching
- **ViewModel**: `ViewModelProvider.Factory` + `by viewModels{}` delegate
- **Lifecycle**: SetupActions callback with bind/unbind in onResume/onPause
- **Execution IDs**: AtomicInteger for Termux IDs (not nanoTime)
- Path-triggered rules in `rules/` auto-load when editing matching files
- Hard constraints in `architecture-decisions/` loaded by agents on demand
- GitHub issues are the defect/work-item system of record.

## On-Demand References

- Architecture: `docs/architecture.md`
- Feature docs: `docs/features/`
- Plans: `plans/`

