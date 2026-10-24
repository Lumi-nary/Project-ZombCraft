# BUILD_LOG.md — blender-mcp NSIS Build Records

## Build 2026-06-25 (v0.10.0)

**Status:** Pending

### Changes
- **CRITICAL FIX: Tool registration:** `app.py` now calls `register_tools(app)` from `blender_mcp.tools` after `discover_tools()`, registering all ~60 tools with FastMCP (previously only `scene_tools` was registered).
- **CRITICAL FIX: .env → .env.example:** `tauri.conf.json` bundle.resources now references `.env.example` instead of `.env`. `build.ps1` bundles `.env.example` from repo root. Created `.env.example` with configurable env vars (no secrets).
- Dashboard: exponential backoff health polling ([1,2,4,8,16]s instead of fixed 10s)
- Dashboard: `data-testid` attributes on all KPIs (`kpi-server`, `kpi-tools`, `kpi-blender`, `kpi-ollama`), `backend-dot`, and `dashboard` container
- Dashboard: dynamic tool count from `/api/v1/health` and `/api/v1/diagnostics` (was hardcoded "50+")
- Dashboard: replaced "Pages" KPI with "Ollama" KPI (fleet pattern)
- Backend: `/api/v1/health` now returns `uptime_seconds` and `tool_count`
- Backend: `/api/v1/diagnostics` already returns `tool_count` (pre-existing)
- `hooks.nsh`: added `NSIS_HOOK_POSTINSTALL` for MCP client registration
- `glama.json`: FastMCP framework version 2.14.3+ → 3.4.2
- `llms.txt`: version 0.5.0 → 0.10.0
- `api/mcp.ts`: added `getDiagnostics()` function for `/api/v1/diagnostics`
- Created `.env.example` at repo root

### Cert Pipeline Status
| Gate | Status |
|------|--------|
| TypeScript lint | — |
| Frontend build | — |
| PyInstaller backend | — |
| Frozen binary smoke test | — |
| Size gate (>= 5 MB) | — |
| NSIS build | — |
| CUA-NSIS smoke test | — |
