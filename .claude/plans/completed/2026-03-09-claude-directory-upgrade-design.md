# Design: .claude Directory Upgrade

**Date**: 2026-03-09
**Status**: APPROVED
**Session**: 42
**Approach**: Incremental Migration (dependency order)

## Goal

Bring Tablite's `.claude/` directory to parity with FieldGuide App's documentation, skills, and agent systems. Extract existing knowledge into structured systems — no new content invented, only reorganization and new tooling.

## Decisions

| Decision | Choice |
|----------|--------|
| Agents | Add 2 targeted: termux-shell-agent, auth-agent |
| Writing-plans skill | Yes, add it (CodeMunch + fallback) |
| Rules system | Extract existing CLAUDE.md content into rule files |
| Defect tracking | Per-feature defect files in `defects/` |
| Constraints | Per-package constraint files |
| Agent memory | Yes, for all 5 agents |
| Feature docs | Overview + architecture pairs per package |
| Audit-config skill | Yes |
| Hooks | No |
| Adversarial review | Standalone skill (extracted from brainstorming) |
| Brainstorming | Updated to produce specs, handoff to adversarial-review or writing-plans |
| End/resume session | Updated to match FieldGuide patterns |

---

## Implementation Order

### Step 1: Rules System (6 files)

Extract from CLAUDE.md conventions into path-triggered rule files.

| Rule File | Path Trigger | Content Source |
|-----------|-------------|---------------|
| `rules/coroutine-safety.md` | `**/*.kt` | Coroutine Safety table (dispatcher mapping + rules) |
| `rules/termux-integration.md` | `**/termux/**/*.kt` | Termux gotchas, RUN_COMMAND bundle extraction, FLAG_MUTABLE |
| `rules/shell-scripts.md` | `assets/scripts/**/*.sh` | Shell Script conventions, proot compatibility rules |
| `rules/compose-ui.md` | `**/ui/**/*.kt` | Compose section (state hoisting, collectAsState, no side effects) |
| `rules/installer.md` | `**/installer/**/*.kt` | PackageInstaller gotchas (fsync, signing, STATUS_PENDING_USER_ACTION) |
| `rules/auth.md` | `**/auth/**/*.kt` | OAuth gotchas (Jagex 2-step, GeckoView, credential env vars) |

Each file has YAML frontmatter with `paths:` key for auto-loading. CLAUDE.md keeps summary tables with "see rules/{file} for details" pointers.

### Step 2: Architecture Decisions (7 files)

Per-package constraint files derived from "Unique Solved Problems", "Common Gotchas", and defect patterns. Each file has Hard Rules (reject proposal) and Soft Guidelines (discuss).

| Constraint File | Content Source |
|----------------|---------------|
| `architecture-decisions/termux-constraints.md` | Solved Problems #1, #4, #5; Termux gotchas; proot fd warnings, kill-on-exit |
| `architecture-decisions/auth-constraints.md` | Solved Problems #2, #3; OAuth 2-step flow rules; GeckoView requirement; session expiry |
| `architecture-decisions/installer-constraints.md` | PackageInstaller gotchas (fsync, signing conflict, STATUS_PENDING_USER_ACTION) |
| `architecture-decisions/setup-constraints.md` | Solved Problems #8, #9; StateFlow reactivity; sealed class lazy init; step orchestration |
| `architecture-decisions/shell-constraints.md` | Solved Problems #5, #6, #7; bash -c escaping; proot exit codes; CRLF shebangs |
| `architecture-decisions/ui-constraints.md` | Compose conventions; Solved Problem #10 (TimeoutCancellationException catch order) |
| `architecture-decisions/shared-constraints.md` | Cross-cutting: coroutine CancellationException rules, no GlobalScope, structured concurrency, OkHttp response.use{} |

### Step 3: Per-Feature Defect Files (7 files)

Migrate from single `autoload/_defects.md` to `defects/` directory. Max 5 per file; rotation to `logs/defects-archive.md`.

| File | Seeded From |
|------|------------|
| `defects/_defects-auth.md` | [ANDROID] Auth refresh Step 1/3; GeckoAuthActivity process death; EncryptedSharedPreferences corruption |
| `defects/_defects-shell.md` | [SHELL] Double quotes inside bash -c blocks |
| `defects/_defects-setup.md` | [ANDROID] reconcileWithMarkers stateStore; resetSetup orchestrator state |
| `defects/_defects-security.md` | [SECURITY] shellEscape metacharacters |
| `defects/_defects-termux.md` | (empty placeholder) |
| `defects/_defects-installer.md` | (empty placeholder) |
| `defects/_defects-ui.md` | (empty placeholder) |

**Removed**: `autoload/_defects.md` — contents migrated to per-feature files.

### Step 4: Feature Documentation (13 files)

Per-package overview + architecture pairs in `docs/features/`. Content extracted from `docs/architecture.md`, `MEMORY.md`, and `research/`.

| Package | Overview | Architecture |
|---------|---------|-------------|
| Termux | `feature-termux-overview.md` — RUN_COMMAND, Bundle extraction, execution IDs | `feature-termux-architecture.md` — CompletableDeferred callback, threading model |
| Auth | `feature-auth-overview.md` — Jagex 3-step OAuth, GeckoView, credential env vars | `feature-auth-architecture.md` — JagexOAuth2Manager, CredentialManager, GeckoAuthActivity |
| Installer | `feature-installer-overview.md` — APK download + install flow | `feature-installer-architecture.md` — ApkDownloader, ApkInstaller, InstallResultReceiver |
| Setup | `feature-setup-overview.md` — 7 setup steps, orchestration | `feature-setup-architecture.md` — SetupOrchestrator, SetupViewModel, StateFlow pipeline |
| UI | `feature-ui-overview.md` — Single-screen Compose, Material 3 | `feature-ui-architecture.md` — SetupScreen, StepItem, state hoisting, theme |
| Shell | `feature-shell-overview.md` — setup-environment.sh, launch-runelite.sh | `feature-shell-architecture.md` — proot-distro, rootfs, X11 bind-mount, GPU tiered fallback |

Plus `docs/features/README.md` mapping packages to primary agents.

### Step 5: Agent Memory (5 files)

Create `agent-memory/{agent}/MEMORY.md` for all agents. Agents declare `memory: project` in frontmatter.

| Agent | Seeded From |
|-------|------------|
| `agent-memory/code-review-agent/MEMORY.md` | Recurring patterns from defects archive |
| `agent-memory/performance-agent/MEMORY.md` | Known performance concerns from agent definition |
| `agent-memory/security-review-agent/MEMORY.md` | Accepted risks from agent definition |
| `agent-memory/termux-shell-agent/MEMORY.md` | MEMORY.md: Termux RUN_COMMAND, proot, paths, X11 |
| `agent-memory/auth-agent/MEMORY.md` | MEMORY.md: Auth facts, OAuth 2-step, session expiry |

### Step 6: New Agents (2 files)

#### `agents/termux-shell-agent.md`

| Property | Value |
|----------|-------|
| Model | Sonnet (implementation), Opus (review) |
| Tools | Read, Edit, Write, Bash, Glob, Grep |
| Memory | `agent-memory/termux-shell-agent/MEMORY.md` |
| Owns | `**/termux/**/*.kt`, `assets/scripts/**/*.sh` |
| Rules | `rules/termux-integration.md`, `rules/shell-scripts.md` |
| Constraints | `architecture-decisions/termux-constraints.md`, `architecture-decisions/shell-constraints.md` |
| Defects | `defects/_defects-termux.md`, `defects/_defects-shell.md` |

Specialization: Termux RUN_COMMAND IPC, shell script authoring, proot-distro, X11/PulseAudio, GPU setup scripts.

#### `agents/auth-agent.md`

| Property | Value |
|----------|-------|
| Model | Sonnet (implementation), Opus (review) |
| Tools | Read, Edit, Write, Bash, Glob, Grep |
| Memory | `agent-memory/auth-agent/MEMORY.md` |
| Owns | `**/auth/**/*.kt` |
| Rules | `rules/auth.md` |
| Constraints | `architecture-decisions/auth-constraints.md` |
| Defects | `defects/_defects-auth.md` |

Specialization: Jagex 3-step OAuth, GeckoView navigation interception, CredentialManager, session validation/refresh, token lifecycle.

Both agents include `## When Used by /implement` section with P0/P1/P2 severity protocol.

### Step 7: Specs Directory + Updated Brainstorming

- Create `specs/` directory
- Update `skills/brainstorming/SKILL.md`:
  - Output to `specs/YYYY-MM-DD-<topic>-spec.md` (was `plans/`)
  - Add "Affected Packages" and "Constraint References" sections to spec format
  - Handoff options: `/adversarial-review` (optional) or `/writing-plans`
  - Context loading: read per-feature defect files and constraint files for relevant package
  - Three phases unchanged, Iron Law unchanged

### Step 8: Writing-Plans Skill

New `skills/writing-plans/SKILL.md`.

**Input**: Approved spec from `specs/`
**Output**: Phased plan at `plans/YYYY-MM-DD-<feature-name>.md`

Workflow:
1. Read approved spec
2. Index codebase (CodeMunch MCP, fallback Glob+Grep)
3. Build dependency graph
4. Determine blast radius (Direct/Dependent/Test/Cleanup)
5. Save analysis to `dependency_graphs/YYYY-MM-DD-<name>/`
6. Write plan with Phase > Step hierarchy and agent routing table
7. Present to user for approval
8. Save to `plans/`

Hard gate: Cannot write plan steps until spec is read and blast radius analyzed.

Agent routing table embedded in plans:

| File Pattern | Agent |
|-------------|-------|
| `**/termux/**/*.kt`, `assets/scripts/**/*.sh` | `termux-shell-agent` |
| `**/auth/**/*.kt` | `auth-agent` |
| `**/installer/**/*.kt` | Main session |
| `**/setup/**/*.kt` | Main session |
| `**/ui/**/*.kt` | Main session |
| Review (any) | `code-review-agent`, `security-review-agent` |
| Performance (any) | `performance-agent` |

New directory: `dependency_graphs/`

### Step 9: Adversarial-Review Skill

New `skills/adversarial-review/SKILL.md`.

**Input**: Spec from `specs/` or plan from `plans/`
**Output**: Review at `adversarial_reviews/YYYY-MM-DD-<topic>/review.md`

Workflow:
1. Accept file path (spec or plan)
2. Read document + relevant constraints + per-feature defects
3. Dispatch in parallel:
   - `code-review-agent` (Opus) — architecture, completeness, constraint violations
   - `security-review-agent` (Opus) — security implications, injection vectors
4. Categorize: MUST-FIX (blocks) or SHOULD-CONSIDER (advisory)
5. Save to `adversarial_reviews/`
6. Present with options: address, defer, dismiss

Properties: Read-only, optional in pipeline, reusable on specs or plans.

New directory: `adversarial_reviews/`

### Step 10: Audit-Config Skill

New `skills/audit-config/SKILL.md`.

**Output**: Report at `outputs/audit-report-YYYY-MM-DD.md`

7 validation steps:
1. Index codebase (CodeMunch, fallback Glob+Grep)
2. Scan `.claude/` files for path/class references
3. Validate references exist on disk
4. Structural invariants (agent-memory dirs, rule frontmatter, constraint files, doc pairs, defect files)
5. Security invariants (disallowedTools on review agents, CLAUDE.md sentinels)
6. Produce report (broken paths, orphaned files, missing coverage, stale references)
7. Save to `outputs/`, present options (fix auto, fix manual, defer)

Iron Law: NEVER modifies files. Read-only and report-only.

New directory: `outputs/`

### Step 11: Updated End-Session + Resume-Session

#### `/end-session` updates

1. Gather summary from conversation context (unchanged)
2. Update `autoload/_state.md` — session entry, rotation if >5 (unchanged)
3. **Changed**: Write defects to `defects/_defects-{package}.md` (max 5 per file, rotate to `logs/defects-archive.md`)
4. Update JSON state — PROJECT-STATE.json, FEATURE-MATRIX.json, feature-{name}.json (unchanged)
5. Display summary with `/resume-session` reminder (unchanged)

Closely mirrors FieldGuide's end-session pattern.

#### `/resume-session` updates

1. Read exactly 2 files: `memory/MEMORY.md` + `autoload/_state.md`
2. Display compact summary (Phase, Status, Last Session, Next Tasks)
3. Return control — defects demand-loaded by agents, not on resume

Closely mirrors FieldGuide's resume-session pattern.

**Removed**: `autoload/_defects.md`

### Step 12: CLAUDE.md Update

**Add**: "Domain Rules" section with rule file → path trigger table
**Update**: Agents table — add termux-shell-agent, auth-agent
**Update**: Skills table — add writing-plans, adversarial-review, audit-config
**Update**: "When to Use What":
- New feature: `/brainstorming` → `/adversarial-review` (optional) → `/writing-plans` → `/implement`
- Spec/plan quality: `/adversarial-review`
- .claude/ health: `/audit-config`

**Update**: Directory Reference — add all new directories
**Update**: Session section — remove `_defects.md` reference, note per-feature defects
**Trim**: Move detailed convention content to rule files, keep summary + pointers

---

## New Directories Summary

| Directory | Purpose |
|-----------|---------|
| `rules/` | Path-triggered convention rule files |
| `architecture-decisions/` | Per-package hard rules and soft guidelines |
| `defects/` | Per-feature defect pattern files |
| `docs/features/` | Per-package overview + architecture docs |
| `agent-memory/` | Per-agent persistent memory |
| `specs/` | Brainstorming spec output |
| `adversarial_reviews/` | Adversarial review reports |
| `dependency_graphs/` | Writing-plans blast radius analysis |
| `outputs/` | Audit-config reports |

## File Count

- **New files**: ~45
- **Updated files**: ~5 (CLAUDE.md, brainstorming SKILL.md, end-session SKILL.md, resume-session SKILL.md, existing agent frontmatter updates)
- **Removed files**: 1 (`autoload/_defects.md`)

## Pipeline After Upgrade

```
brainstorming (spec in specs/)
    → [optional] adversarial-review (review in adversarial_reviews/)
    → writing-plans (plan in plans/, graph in dependency_graphs/)
    → implement (code + quality gates)
    → end-session (state + per-feature defects + JSON)
```
