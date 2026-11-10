# Agent Guidelines

## Agent skills

### Issue tracker
When managing issues, bugs, or task specs, use GitHub Issues (`BanShan-Alec/LynkCoPassiveKey`) via `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels
When triaging or labeling issues, use the standard vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs
When exploring codebase or naming domain concepts, align with `GLOSSARY.md` and check relevant ADRs in `docs/adr/`. See `docs/agents/domain.md`.

### UI & Design System
When modifying UI, layouts, styles, or colors, follow the Paseo Dark tokens and Refactoring UI constraints in `DESIGN.md`.

### Build & Verification
When verifying logic or compiling APK, run unit tests and builds inside the reproducible Docker container:
`docker run --rm -v "${PWD}:/workspace" -w /workspace/GeelyKeyRadarApp lynkco-key-builder:latest bash build.sh`
