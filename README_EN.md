# Eta — Personal Custom Fork

[简体中文](README.md) | **English**

> This is a **personal custom fork** of [Mangi-11/Eta](https://github.com/Mangi-11/Eta). It is not an official release and does not represent the original author.
> The base is upstream 3.3.3 — the nine commits behind 3.3.2 / 3.3.3 were reviewed one by one and merged individually (no wholesale merge). Everything above that is my own tinkering, driven by daily use.
> **Source only.** This repository does not ship prebuilt APKs; see "License and distribution" below for why.

## What this fork is about

Upstream Eta is already a complete phone agent: agent runtime, GUI agent, terminal, system APIs and Xposed entry points are all wired up. This fork does not touch that skeleton. It does two things:

1. Rebuild parts that exist upstream but feel awkward in daily use (Markdown reading and editing, conversation management, status display).
2. Add things upstream does not have that clearly help on ColorOS with root (Fluid Cloud, floating capsule, virtual-display mini window, workspace file browsing).

Every comparison below is against **upstream 3.3.3**; it may drift as upstream moves. The code is the source of truth.

## Differences from upstream

### Notifications and status

- **ColorOS Fluid Cloud live activity**: run progress is surfaced in Fluid Cloud. "Keep Fluid Cloud" lets the idle card stay after a task finishes; tap it to return to Eta.
- **Configurable floating capsule**: keep it always on, or switch to "Fluid Cloud only while running" so the status bar does not stack two indicators.
- Slimmer status capsule: low-frequency states move into a menu; model thinking and the current step stay visible.

### Virtual display

- The virtual display can show a **draggable mini window**; drop it on either edge and it collapses into a narrow handle.
- Touch injection supports a **continuous gesture protocol**, so swipes and long-press drags are no longer split into a series of taps.

### Conversations and sidebar

- **Concurrent sessions**: the upstream runtime is essentially one session at a time; here sessions can run and be archived side by side (experimental — please open an issue if it misbehaves).
- **Categories**: group conversations by category (study / daily / project …), long-press to "Move to category" or drag into a category section (auto-scrolls at the edges); a chat started inside a category inherits it.
- **Session navigator**: swipe up on the small bar above the input to open a searchable outline and jump to any turn without scrolling back.
- Pinned / favourite conversations and sidebar search.

### Markdown

- **Markdown file cards**: `.md` files in chat render as cards — tap to preview and edit in place; the pen button hands the file to MT Manager.
- **Rebuilt preview**: a bottom sheet that follows your finger, collapses on pull-down, and syncs its dismissal animation with the back gesture; the menu anchors to the ⋯ button.
- **Offline KaTeX**: inline and block formulas render without network access.
- **Tappable task lists** that write back to the source file, a line-wrap toggle for code blocks, and long-press to copy the whole Markdown.

### Workspace

- Built-in **workspace file browser**: browse the agent's private workspace, preview and edit Markdown, and open files produced under root.

### Tools, skills and commands

- **Tools and skills panel**: MCP and Skills toggles move above the input, with counters (for example MCP 1/2, skills 4/7, 57/57 tools per server).
- **@ to quote earlier messages** into the current input.
- **Slash commands**: built-ins plus every installed skill — even a switched-off skill can be run with `/skill-id task` (marked "disabled" in the palette) and its `SKILL.md` is injected into that turn; user bubbles get a `/skill-id · skill` header so they read differently from plain messages. `/help`, `/skills` and `/model` open a scrollable, tappable palette (tap a model to switch); `/status`, `/effort` and error messages use a dismissible result card above the input instead of a two-second toast; truncated skills or calling a disabled skill leave a compact notice in the timeline. The palette sizes itself to the space above the input (never pushing the input behind the keyboard), scrolls, shows two lines of description and expands the full text on long-press; side-effecting commands keep the confirmation card.
- **Tool argument validation** against the schema before execution.
- Per-server compression of MCP tool schemas to save context.

### Input and models

- **Tap send = steer / long-press send = queue**; queued items appear as capsules above the input, with a lightning button to send now and × to delete.
- **Codex / Grok subscription sign-in**: syncs the remote model list, context window and thinking levels; the built-in OpenAI and xAI subscription entries are kept.
- **Switchable web search and read backend**: built-in default, DeepSeek, or a custom HTTP endpoint.

### Voice

- Reading resumes where it stopped; the app recovers automatically after a system TTS timeout.
- Speech output supports Gemini and MiniMax, with per-service voice settings.
- **Voice recognition can be disabled entirely**; the microphone entries in the input bar and overlay disappear with it.

### Build and size

- **ARM64 native libraries only**, which shrinks the APK noticeably.
- Lower memory use for long conversations.
- **Simplified Chinese as the default** (Chinese-only build; zh-Hant leftovers removed).

## Building

Requires JDK 17+, Android SDK 35 and full network access for the first build:

```bash
git clone https://github.com/Oo0520/Eta-por.git
cd Eta-por
./gradlew assembleRelease
```

- Output: `app/build/outputs/apk/release/`
- **No signing material is included**: sign with your own keystore; APKs signed with a different key cannot be installed over an existing install.
- ARM64-only is a size trade-off; adjust `abiFilters` in `app/build.gradle.kts` if you need other ABIs.
- `versionCode` in this fork uses its own numbering (for example 2026102259) and does not map one-to-one to upstream releases.

## License and distribution

This repository inherits the upstream license: [PolyForm Noncommercial License 1.0.0](LICENSE). `LICENSE` and the copyright notice are kept verbatim, including:

> Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

Under that license you may read, modify and build for your own use, and redistribute the source as the license allows (shipping the license terms and the notice above with it).

I also respect the extra note in the upstream README: **without the author's written permission, do not redistribute builds, sell them, or offer paid installations.** That is why this repository carries source only and no APK download. If you want to use this fork commercially, ask the original author for written permission first.

## Upstream docs

Everything under `docs/` is synced with upstream and still applies to this fork's base, for example:

- [Agent Runtime](docs/AGENT_RUNTIME.md), [automatic memory and experience learning](docs/EXPERIENCE_LEARNING.md), [scheduled and event tasks](docs/AUTOMATION.md)
- [Root virtual display](docs/VIRTUAL_SCREEN.md), [device support and permission boundaries](docs/ROOTLESS_SUPPORT.md), [technical notes](docs/TECHNICAL.md)
- [Voice configuration](docs/VOICE.md), [character features](docs/CHARACTERS.md)

For the full description of upstream features, see the [Mangi-11/Eta README](https://github.com/Mangi-11/Eta).

## Credits

More than ninety percent of this work is not mine:

- [Mangi-11](https://github.com/Mangi-11) — built Eta itself. System APIs, terminal, GUI agent and Xposed entry points are fused into one agent runtime; without that foundation none of the changes above would exist.
- The author of the community customization patch — Fluid Cloud and the virtual-display mini window started there; this fork ported them to 3.3.x.
- [Pi Coding Agent](https://github.com/earendil-works/pi), [Hermes Agent](https://github.com/NousResearch/hermes-agent), [OmniBot](https://github.com/omnimind-ai/OmniBot), [libxposed API](https://github.com/libxposed/api), [Miuix](https://github.com/compose-miuix-ui/miuix) — core runtime references and the UI component library.

None of this is product design; it is just what made the app feel right for one user. If any of it is useful, take the approach and build your own.
