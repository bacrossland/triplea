# Headless Server Password Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the headless dedicated TripleA server ("bot host") require a password to join, gated behind a new optional `BOT_PASSWORD` environment variable, without touching the already-working headed (GUI) password flow or any lobby-side code.

**Architecture:** `HeadlessGameRunner.main()` resolves an optional password from the `BOT_PASSWORD` env var (or the `triplea.server.password` system property, which wins if set) and, when non-blank, sets it as the `triplea.server.password` system property and relaxes the mandatory `Bot`-name-prefix validation for `TRIPLEA_NAME`. `HeadlessServerSetupModel` reads that same system property and passes it to `ClientLoginValidator`, exactly like `HeadedServerSetupModel` already does for the headed flow. Everything downstream (challenge/response auth, the client's password dialog, the lobby's "passworded" indicator) already exists and requires no changes.

**Tech Stack:** Java 25, Gradle, JUnit 5, Hamcrest, Mockito, Guava (`Strings`, `@VisibleForTesting`).

## Global Constraints

- Scope is limited to `game-app/game-headless`. Do not modify the headed flow, `ClientLoginValidator`, `ClientLogin`, `InGameLobbyWatcher`, or any lobby/http-client code — the spec confirms these already handle the passworded case generically.
- `BOT_PASSWORD` absent or blank must be a fully backward-compatible no-op — never a startup failure (unlike the existing mandatory `BOT_NAME`/`BOT_PORT`/`BOT_LOBBY_URI` checks).
- No minimum length or complexity rule on the password value.
- No changes to the `ClientLoginValidator` authentication protocol itself.
- Follow existing code conventions exactly: JUnit 5 (`org.junit.jupiter.api.Test`), Hamcrest `assertThat`/`is`, Guava `@VisibleForTesting` for test-only visibility, package-private static helper methods (matching `isInvalidPortNumber`/`setSystemProperty` already in `HeadlessGameRunner`).

---

### Task 1: `HeadlessGameRunner` — optional `BOT_PASSWORD` support

**Files:**
- Modify: `game-app/game-headless/src/main/java/org/triplea/game/server/HeadlessGameRunner.java`
- Test: `game-app/game-headless/src/test/java/org/triplea/game/server/HeadlessGameRunnerTest.java` (new file)

**Interfaces:**
- Produces: `HeadlessGameRunner.isValidBotName(String name, boolean passwordProtected): boolean` (package-private static, `@VisibleForTesting`)
- Produces: `HeadlessGameRunner.resolveServerPassword(String systemPropertyValue, String envVarValue): String` (package-private static, `@VisibleForTesting`) — mirrors `System.getProperty(key, default)` semantics: returns `systemPropertyValue` unchanged if it is non-`null` (even if empty), otherwise `envVarValue` normalized to `""` if `null`.
- Produces: `HeadlessGameRunner.BOT_PASSWORD_ENV = "BOT_PASSWORD"` (private constant, referenced only within this file)

- [ ] **Step 1: Write the failing tests**

Create `game-app/game-headless/src/test/java/org/triplea/game/server/HeadlessGameRunnerTest.java`:

```java
package org.triplea.game.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class HeadlessGameRunnerTest {

  @Test
  void validBotNameRequiresBotPrefixAndLengthWhenNotPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("Bot_Local01", false), is(true));
  }

  @Test
  void tooShortNameIsInvalidWhenNotPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("Bot12", false), is(false));
  }

  @Test
  void tooLongNameIsInvalidWhenNotPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("Bot_WayTooLongOfAName1", false), is(false));
  }

  @Test
  void nameWithoutBotPrefixIsInvalidWhenNotPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("NotABotName", false), is(false));
  }

  @Test
  void anyNonBlankNameIsValidWhenPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("ClanNightGame", true), is(true));
  }

  @Test
  void blankNameIsInvalidEvenWhenPasswordProtected() {
    assertThat(HeadlessGameRunner.isValidBotName("", true), is(false));
    assertThat(HeadlessGameRunner.isValidBotName(null, true), is(false));
  }

  @Test
  void systemPropertyWinsOverEnvVarWhenBothSet() {
    assertThat(HeadlessGameRunner.resolveServerPassword("fromSystemProperty", "fromEnvVar"), is("fromSystemProperty"));
  }

  @Test
  void envVarIsUsedWhenSystemPropertyIsUnset() {
    assertThat(HeadlessGameRunner.resolveServerPassword(null, "fromEnvVar"), is("fromEnvVar"));
  }

  @Test
  void emptySystemPropertyMeansExplicitlyNoPasswordAndDoesNotFallBackToEnvVar() {
    assertThat(HeadlessGameRunner.resolveServerPassword("", "fromEnvVar"), is(""));
  }

  @Test
  void resolvesToEmptyStringWhenNeitherIsSet() {
    assertThat(HeadlessGameRunner.resolveServerPassword(null, null), is(""));
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `./gradlew :game-headless:test --tests "org.triplea.game.server.HeadlessGameRunnerTest"`
Expected: FAIL — compile error, `isValidBotName` and `resolveServerPassword` are not defined on `HeadlessGameRunner`.

- [ ] **Step 3: Add the two helper methods and the new constant**

In `HeadlessGameRunner.java`, add these imports (keep the existing static-import block alphabetical):

```java
import static games.strategy.engine.framework.CliProperties.SERVER_PASSWORD;
```
(insert between the existing `LOBBY_URI` and `TRIPLEA_NAME` static imports)

and these regular imports (alongside the existing ones):

```java
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
```

Add the new env var constant next to the existing `BOT_*_ENV` constants:

```java
  private static final String BOT_COMMENT_ENV = "BOT_COMMENT";
  private static final String BOT_NAME_ENV = "BOT_NAME";
  private static final String BOT_PORT_ENV = "BOT_PORT";
  private static final String BOT_LOBBY_URI_ENV = "BOT_LOBBY_URI";
  private static final String BOT_PASSWORD_ENV = "BOT_PASSWORD";
```

Add the two new static methods at the bottom of the class, next to `isInvalidPortNumber`:

```java
  @VisibleForTesting
  static String resolveServerPassword(
      final String systemPropertyValue, final String envVarValue) {
    return systemPropertyValue != null ? systemPropertyValue : Strings.nullToEmpty(envVarValue);
  }

  @VisibleForTesting
  static boolean isValidBotName(final String name, final boolean passwordProtected) {
    if (passwordProtected) {
      return !Strings.isNullOrEmpty(name);
    }
    return name.length() >= 7
        && name.length() <= 21
        && name.startsWith(GameRunner.BOT_GAME_HOST_NAME_PREFIX);
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :game-headless:test --tests "org.triplea.game.server.HeadlessGameRunnerTest"`
Expected: PASS, all 10 tests green.

- [ ] **Step 5: Wire the helpers into `main()`**

In `main()`, immediately after the two existing lines that set `TRIPLEA_HEADLESS` and `TRIPLEA_SERVER` and before the `LOBBY_GAME_COMMENTS` validation block, insert:

```java
    final String password =
        resolveServerPassword(System.getProperty(SERVER_PASSWORD), System.getenv(BOT_PASSWORD_ENV));
    final boolean passwordProtected = !password.isEmpty();
    if (passwordProtected) {
      System.setProperty(SERVER_PASSWORD, password);
    }
```

Then replace the existing `TRIPLEA_NAME` validation block:

```java
    if (!setSystemProperty(
        TRIPLEA_NAME,
        BOT_NAME_ENV, //
        v ->
            v.length() >= 7
                && v.length() <= 21
                && v.startsWith(GameRunner.BOT_GAME_HOST_NAME_PREFIX))) {
      log.error(
          "Invalid value for bot name, must start with the word BOT and be length at least 7, under 22, value: {}");
      ExitStatus.FAILURE.exit();
    }
```

with:

```java
    if (!setSystemProperty(
        TRIPLEA_NAME,
        BOT_NAME_ENV, //
        v -> isValidBotName(v, passwordProtected))) {
      log.error(
          passwordProtected
              ? "Invalid value for bot name, must not be blank, value found: {}"
              : "Invalid value for bot name, must start with the word BOT and be length at "
                  + "least 7, under 22, value: {}");
      ExitStatus.FAILURE.exit();
    }
```

- [ ] **Step 6: Run the full module test suite**

Run: `./gradlew :game-headless:test`
Expected: PASS, no regressions in existing tests (`HeadlessServerSetupTest`, `HeadlessGameRunnerTest`).

- [ ] **Step 7: Commit**

```bash
git add game-app/game-headless/src/main/java/org/triplea/game/server/HeadlessGameRunner.java \
        game-app/game-headless/src/test/java/org/triplea/game/server/HeadlessGameRunnerTest.java
git commit -m "Add optional BOT_PASSWORD support to headless server runner"
```

---

### Task 2: `HeadlessServerSetupModel` — pass the password to `ClientLoginValidator`

**Files:**
- Modify: `game-app/game-headless/src/main/java/org/triplea/game/server/HeadlessServerSetupModel.java`

**Interfaces:**
- Consumes: `CliProperties.SERVER_PASSWORD` (existing constant, same one Task 1 sets), `ClientLoginValidator.builder(): ClientLoginValidatorBuilder` with existing `.password(String)` and `.serverMessenger(IServerMessenger)` methods (both already exist, used identically by `HeadedServerSetupModel`).

- [ ] **Step 1: Add the static import**

In `HeadlessServerSetupModel.java`, add, keeping the block alphabetical:

```java
import static games.strategy.engine.framework.CliProperties.SERVER_PASSWORD;
```
(insert after the existing `LOBBY_URI` static import)

- [ ] **Step 2: Wire the password into the login validator**

Replace:

```java
    serverModel
        .getMessenger()
        .setLoginValidator(
            ClientLoginValidator.builder().serverMessenger(serverModel.getMessenger()).build());
```

with:

```java
    serverModel
        .getMessenger()
        .setLoginValidator(
            ClientLoginValidator.builder()
                .password(System.getProperty(SERVER_PASSWORD))
                .serverMessenger(serverModel.getMessenger())
                .build());
```

- [ ] **Step 3: Run the module test suite to confirm no regression**

Run: `./gradlew :game-headless:test`
Expected: PASS — `HeadlessServerSetupTest` is unaffected by this change (it doesn't exercise `HeadlessServerSetupModel`), confirming nothing broke.

- [ ] **Step 4: Commit**

```bash
git add game-app/game-headless/src/main/java/org/triplea/game/server/HeadlessServerSetupModel.java
git commit -m "Wire headless server password into ClientLoginValidator"
```

---

### Task 3: Deployment docs — document `BOT_PASSWORD`

**Files:**
- Modify: `game-app/game-headless/README.md`
- Modify: `game-app/game-headless/Dockerfile`

- [ ] **Step 1: Update the README's prod-run example**

In `game-app/game-headless/README.md`, under `### Linux:`, replace:

```
BOT_NAME=....change_me.....

sudo ufw allow 4000
docker pull ghcr.io/triplea-game/bot:latest
MAPS_FOLDER=/home/$USER/triplea/downloadedMaps
docker run \
    --env BOT_NAME=$BOT_NAME \
    --env LOBBY_URI=https://prod.triplea-game.org \
    -v $MAPS_FOLDER:/downloadedMaps \
    -p 4000:4000 \
    ghcr.io/triplea-game/bot:latest
```

with:

```
BOT_NAME=....change_me.....
# Optional: set to require players to enter a password before they can join.
# When set, the "BOT" name-prefix convention below is not enforced.
BOT_PASSWORD=....optional, leave blank for a public, unlocked bot.....

sudo ufw allow 4000
docker pull ghcr.io/triplea-game/bot:latest
MAPS_FOLDER=/home/$USER/triplea/downloadedMaps
docker run \
    --env BOT_NAME=$BOT_NAME \
    --env BOT_PASSWORD=$BOT_PASSWORD \
    --env LOBBY_URI=https://prod.triplea-game.org \
    -v $MAPS_FOLDER:/downloadedMaps \
    -p 4000:4000 \
    ghcr.io/triplea-game/bot:latest
```

- [ ] **Step 2: Update the Dockerfile**

In `game-app/game-headless/Dockerfile`, replace:

```
ENV BOT_NAME=BotDocker01
ENV LOBBY_URI=http://localhost:3000
```

with:

```
ENV BOT_NAME=BotDocker01
ENV BOT_PASSWORD=""
ENV LOBBY_URI=http://localhost:3000
```

and replace:

```
CMD java \
   -Xmx$BOT_MAX_MEMORY \
   -Xss$BOT_XSS \
   -Dtriplea.name=$BOT_NAME \
   -Dtriplea.port=$BOT_PORT_NUMBER \
   -Dtriplea.lobby.uri=$LOBBY_URI \
   -Dtriplea.exit.on.game.end=$EXIT_ON_GAME_END \
   -jar game-headless.jar
```

with:

```
CMD java \
   -Xmx$BOT_MAX_MEMORY \
   -Xss$BOT_XSS \
   -Dtriplea.name=$BOT_NAME \
   -Dtriplea.server.password=$BOT_PASSWORD \
   -Dtriplea.port=$BOT_PORT_NUMBER \
   -Dtriplea.lobby.uri=$LOBBY_URI \
   -Dtriplea.exit.on.game.end=$EXIT_ON_GAME_END \
   -jar game-headless.jar
```

- [ ] **Step 3: Verify the Dockerfile still builds**

Run: `docker build -t triplea-bot-test game-app/game-headless` (from the repo root, after `./gradlew :game-headless:shadowJar` has produced `game-app/game-headless/build/libs/game-headless.jar`)
Expected: image builds successfully with no errors.

- [ ] **Step 4: Commit**

```bash
git add game-app/game-headless/README.md game-app/game-headless/Dockerfile
git commit -m "Document BOT_PASSWORD for headless server deployment"
```

---

### Task 4: Manual end-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Verify unpassworded behavior is unchanged**

Run: `MAPS_FOLDER=/tmp/triplea-maps ./gradlew :game-headless:run` (create `/tmp/triplea-maps` first if it doesn't exist)
Connect with a headed TripleA client to `127.0.0.1:3300`.
Expected: connects immediately, no password prompt — identical to today's behavior on master.

- [ ] **Step 2: Verify passworded behavior via env var**

Stop the server from Step 1. Run:
`MAPS_FOLDER=/tmp/triplea-maps BOT_PASSWORD=testpass123 ./gradlew :game-headless:run`
Connect with a headed TripleA client to `127.0.0.1:3300`.
Expected: the client shows the "Enter a password to join the game" dialog (from `ClientLogin.promptForPassword`). Entering the wrong password is rejected with "Invalid password"; entering `testpass123` connects successfully.

- [ ] **Step 3: Verify the relaxed name validation**

Run: `MAPS_FOLDER=/tmp/triplea-maps BOT_PASSWORD=testpass123 BOT_NAME=ClanNightGame ./gradlew :game-headless:run`
Expected: server starts successfully despite `ClanNightGame` not starting with `Bot` — confirms `isValidBotName`'s relaxed branch is exercised through the real CLI path, not just in unit tests.

- [ ] **Step 4: Confirm no regression when password is explicitly empty**

Run: `MAPS_FOLDER=/tmp/triplea-maps BOT_PASSWORD= ./gradlew :game-headless:run`
Expected: behaves identically to Step 1 (no password prompt, standard `Bot`-name validation back in force) — confirms an explicitly empty value is treated as "no password," matching `SystemPropertyReader.serverIsPassworded()`'s existing blank-string handling.
