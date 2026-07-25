# Headless Server Password Protection — Design

## Problem

The headed (GUI) "host a game" flow already supports optional passwords
end-to-end: `ServerOptions` has a "require password" checkbox, the password
flows through `GameProcess.hostGame()` as the `triplea.server.password`
system property, `HeadedServerSetupModel` wires it into
`ClientLoginValidator`, and joining clients are already prompted for a
password via `ClientLogin` when the server requires one.

The headless dedicated server (`game-app/game-headless`, the unattended
"bot" host typically run in Docker) has no password support at all:
`HeadlessGameRunner` reads no password-related env var, and
`HeadlessServerSetupModel.onServerMessengerCreated` builds its
`ClientLoginValidator` without ever setting a password — a headless-hosted
game can never be password-gated today, regardless of system properties set.

An old branch, `private_server` (2020, largely superseded by refactors
since), previously relaxed the mandatory `BOT`-name-prefix convention for a
headless host when a password was present, and wired the password into the
login validator. That code is not directly reusable — the surrounding
classes have since moved modules and the CLI is now env-var driven — but it
confirms the intended shape of this feature.

## Scope

This change is limited to the headless dedicated server. The headed flow
already fully supports passwords and needs no changes. The public lobby
game listing, `ClientLoginValidator`, `ClientLogin`, and
`InGameLobbyWatcher` already handle the passworded case generically and
need no changes either — a password-protected headless server will still
register with and be listed in the public lobby, showing the same
"passworded" lock indicator lobby users already see for passworded headed
games (`InGameLobbyWatcher` derives this purely from
`SystemPropertyReader.serverIsPassworded()`, i.e. whether the
`triplea.server.password` system property is set).

Direct-connect/unlisted hosting (skipping lobby registration) is
explicitly out of scope for this change.

## Architecture / data flow

```
BOT_PASSWORD env var
  -> HeadlessGameRunner.main() sets -Dtriplea.server.password (if non-empty)
    -> HeadlessGameServer name-validation: relaxes BOT-prefix rule if password set
    -> HeadlessServerSetupModel.onServerMessengerCreated: passes password into ClientLoginValidator
      -> ClientLoginValidator issues password challenge to joining clients (already exists)
      -> InGameLobbyWatcher.serverIsPassworded() already reads the same system property
        -> lobby listing shows the passworded indicator automatically (already exists)
```

If `BOT_PASSWORD` is unset or empty, every step above behaves exactly as it
does today on master — this change is purely additive and backward
compatible.

## Components

### `HeadlessGameRunner` (`game-app/game-headless/.../server/HeadlessGameRunner.java`)

- Add a `BOT_PASSWORD_ENV = "BOT_PASSWORD"` constant.
- Before the existing bot-name validation, read the password:
  `System.getProperty(SERVER_PASSWORD, System.getenv(BOT_PASSWORD_ENV))`.
  If non-blank, set it via `System.setProperty(SERVER_PASSWORD, password)`.
  This is optional — absence is not a startup failure, unlike the existing
  mandatory `BOT_NAME`/`BOT_PORT`/`BOT_LOBBY_URI` checks.
- Extract the existing inline bot-name-validation lambda into a small
  static, named predicate, e.g.
  `isValidBotName(String name, boolean passwordProtected)`, so the rule is
  unit-testable in isolation:
  - `passwordProtected == false`: today's rule — length between 7 and 21,
    starts with `GameRunner.BOT_GAME_HOST_NAME_PREFIX`.
  - `passwordProtected == true`: any non-blank name is accepted.

### `HeadlessServerSetupModel` (same package)

- In `onServerMessengerCreated`, add
  `.password(System.getProperty(SERVER_PASSWORD))` to the existing
  `ClientLoginValidator.builder()` call — mirrors `HeadedServerSetupModel`
  exactly. This is the only change needed to make the headless login
  validator actually enforce a password.

### Docs / deployment

- `game-app/game-headless/README.md`: document `BOT_PASSWORD` alongside the
  existing prod-run example (`BOT_NAME`, `LOBBY_URI`).
- `game-app/game-headless/Dockerfile`: add `ENV BOT_PASSWORD=""` and append
  `-Dtriplea.server.password=$BOT_PASSWORD` to the `CMD java ...`
  invocation. An empty value is harmless —
  `SystemPropertyReader.serverIsPassworded()` treats a blank system
  property as "no password."

### No changes needed

`ClientLoginValidator`, `ClientLogin`, `InGameLobbyWatcher`, and all
lobby-side/http-client code already handle the passworded case generically
today (proven by the already-working headed flow) and require no changes.

## Error handling & validation

- **`BOT_PASSWORD` absent or empty**: no-op; fully backward compatible.
  Today's mandatory `BOT`-prefixed naming rule stays in force, and the
  server is not passworded.
- **`BOT_PASSWORD` non-empty**: password gating turns on via the existing
  `ClientLoginValidator`/lobby-watcher machinery, and `TRIPLEA_NAME`
  validation is relaxed to accept any non-blank name. No minimum length or
  complexity rule is imposed on the password itself.
- **Wrong password from a joining client**: already handled by
  `ClientLoginValidator.verifyConnection`, which returns an
  `"Invalid password"` error and includes an existing random 0-4s sleep as
  basic anti-brute-force throttling. No new error path is introduced.
- No behavior change to the lobby server, `ClientLogin`, or any GUI dialog.

## Testing

- New unit test for the extracted `isValidBotName(String name, boolean
  passwordProtected)` predicate: covers valid/invalid `BOT`-prefixed names
  under today's rule, and confirms arbitrary non-blank names pass when
  `passwordProtected` is `true`.
- Extend or add a small test around the `BOT_PASSWORD` ->
  `triplea.server.password` system-property bridging logic in
  `HeadlessGameRunner`, isolated from the parts of `main()` that call
  `ExitStatus.FAILURE.exit()`.
- `HeadlessServerSetupModel.onServerMessengerCreated` is not currently
  unit-tested (Swing/network-glue code, like its headed counterpart
  `HeadedServerSetupModel`); no new test is added there. The one-line
  change is low-risk and mirrors already-tested behavior — the validator
  itself is covered by the existing `ClientLoginValidatorTest`.
- Manual verification: run the headless server locally via Gradle with
  `BOT_PASSWORD` set and unset, confirm a GUI client is prompted for a
  password only in the former case, and confirm the lobby listing shows
  the passworded indicator.

## Explicitly out of scope

- Direct-connect / unlisted hosting (skipping public lobby registration).
- Any change to the headed (GUI) host-a-game flow, which already fully
  supports passwords today.
- Minimum length or complexity requirements on the password value.
- Any change to `ClientLoginValidator`'s authentication protocol itself.
