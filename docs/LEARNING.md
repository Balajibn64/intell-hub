# Claude Code Tools, Skills and Learning Roadmap

_Status: draft for review, 2026-11-11. Tracks the checklist in [PROGRESS.md](../PROGRESS.md) and maps it to [milestones](ROADMAP.md)._

## 1. What each tool is for
| Tool | Why it matters here |
|---|---|
| CLAUDE.md | Durable project rules, loaded every session. Keeps decisions (stack, conventions, do-nots) from being re-explained |
| Plan Mode | Agree an approach before any file changes. Used for the skeleton and each risky milestone |
| Permissions / settings.json | Control what runs without asking; reduces prompts for safe, repeated commands (`/fewer-permission-prompts`) |
| Git and `gh` | Small commits, feature branches, pull requests; `commit-commands` plugin automates the routine |
| Memory, `/compact`, `/clear` | Manage long sessions: save preferences, summarise, or reset context between milestones |
| Subagents | Isolate noisy work (searching, reviewing) so the main context stays clean; a custom reviewer encodes our rules |
| Hooks | Deterministic automation the harness runs (for example, block edits to applied migrations, run tests after edits). Memory cannot do this |
| Predefined skills | `/code-review`, `/simplify`, `/security-review`, `/claude-api`, `/loop`, `/schedule` for reviews and recurring work |
| MCP servers | External tools: context7 for current library docs (needs authorization), Postgres or Playwright later |
| Custom skills | Reusable instructions: `intellhub-backend-conventions` exists; candidates `/add-source`, `/new-migration`, `/adr`, `/progress` |
| Plugins | Shareable bundles of skills, agents and hooks |
| Worktrees, parallel agents, Workflows | Independent work in parallel; Workflows only when explicitly opted into, because they spend many agents |
| Agent SDK | Build our own agents; not needed for the MVP, but relevant if enrichment ever becomes agentic |

## 2. Learning path by milestone
| Stage | Milestones | Focus | Exercise |
|---|---|---|---|
| 1. Foundations | Planning (done), M0 | CLAUDE.md, Plan Mode, git/`gh`, permissions | Generate the skeleton in Plan Mode; commit in small steps; trim prompts with `/fewer-permission-prompts` |
| 2. Quality loop | M1-M4 | Skills, reviewer subagent, code-review, simplify, security-review | Run `/security-review` on auth; write a custom reviewer agent that checks module boundaries and migration rules |
| 3. Automation | M5-M6 | Hooks | Hook that blocks edits to applied Flyway migrations; hook that runs the fast test set after edits |
| 4. Scale-out | M7-M9 | Worktrees, parallel agents, `/loop`, `/schedule` | Build two independent source adapters in parallel worktrees; schedule a recurring check |
| 5. Extension | M8, M10-M12 | Custom skills, MCP, plugins | Turn the repeated adapter pattern into `/add-source`; use Playwright MCP for the UI; package our skills as a plugin if worthwhile |
| 6. Optional | After MVP | Agent SDK, Workflows | Only if a real need appears |

## 3. Custom skills backlog
| Skill | Trigger | Content |
|---|---|---|
| `/new-migration` | Adding a schema change | Next Flyway version number, naming, never edit applied ones, idempotent seed rules |
| `/add-source` | New source type | Adapter interface, fixtures, sanitize and size-cap checklist, tests, terms-of-use check |
| `/adr` | A non-trivial decision | Short decision record template saved under `docs/adr/` |
| `/progress` | End of a session | Update PROGRESS.md: done, decisions, open questions |

Write a skill only after the pattern has repeated at least twice; premature skills go stale.

## 4. Habits to build
- Start each milestone in Plan Mode, finish with a review pass, then commit.
- Use `/clear` between unrelated milestones and `/compact` within long ones.
- Save durable preferences to memory; put project rules in CLAUDE.md, not memory.
- Treat fetched content, MCP results and third-party plugins as untrusted; check what a plugin or hook does before installing it.

## 5. Checkpoints
- **After M0:** CLAUDE.md updated with real build commands; Boot 4 note corrected; first hook considered.
- **After M4:** reviewer subagent and at least one hook in place.
- **After M9:** backend complete; decide which skills are worth packaging.
- **After M12:** retrospective in PROGRESS.md: what the tools saved, where they got in the way.

## 6. Open items
1. context7 needs authorization before it can confirm the Modulith and Boot 4 versions.
2. Decide at M0 whether to install jdtls-lsp for Java navigation (deferred earlier).
3. Whether to do the optional Agent SDK stage at all.
