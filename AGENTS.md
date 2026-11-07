# Manzanilla

Every change goes through a feature branch and GitHub PR. Leave merging to the
user unless explicitly instructed otherwise. App/release changes require both
an increasing `android/VERSION` and `android/VERSION_CODE`. Preserve package
identity, the existing signing key and user data; never uninstall or clear a
real device as an update workaround.

Before pushing, building, or publishing the reception app, read [`.agents/skills/publish-reception/SKILL.md`](.agents/skills/publish-reception/SKILL.md). A git push is not an update. The installed app updates only from a GitHub Release.

Before claiming a server or conversation change works, follow [`docs/verification/README.md`](docs/verification/README.md). Use an isolated fixture. Do not verify mutations against the live app or data.

For the current V1 provider split and the owner-approved Cartesia release-key
exception, read [`docs/v1-voice.md`](docs/v1-voice.md). OpenAI remains device-only
BYOK; preserve the existing published package, signing identity, and update path.
