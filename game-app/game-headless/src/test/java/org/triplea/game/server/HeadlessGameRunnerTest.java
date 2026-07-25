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
