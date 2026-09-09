package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Does casting a SECOND copy of a legend you already control latch Storied?
 *
 * Storied is built as `Mode$ Always | TriggerZones$ Battlefield |
 * IsPresent$ Permanent.YouCtrl+Historic | PresentCompare$ GE3` (CardFactoryUtil, keyword Storied),
 * and GameAction.checkStateEffects evaluates Always triggers at the head of each state-based-action
 * pass (checkStaticAbilities, line ~1421) BEFORE handleLegendRule runs later in that same pass. So
 * the duplicate should be counted once, before it dies -- and because Player.setEnduringStory is
 * only ever called with true, that one look is permanent.
 *
 * This asserts the ordering rather than trusting a reading of it: the game log never announces the
 * story, so nothing short of asking the engine can confirm it.
 */
public class StoriedLegendRuleTest extends AITest {

    /** Ori carries the keyword; Nori is a second historic permanent (neither makes a token). Two is below the threshold. */
    @Test
    public void twoHistoricPermanentsDoNotLatchTheStory() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        addCard("Ori, Keeper of Songs", p);
        addCard("Nori, Teller of Tales", p);
        game.getAction().checkStateEffects(true);

        Assert.assertFalse(p.hasEnduringStory(),
                "two historic permanents should be below the GE3 threshold");
    }

    /** The whole question: the third permanent is a duplicate legend that cannot survive. */
    @Test
    public void aDuplicateLegendLatchesTheStoryBeforeDying() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        addCard("Ori, Keeper of Songs", p);
        addCard("Nori, Teller of Tales", p);
        game.getAction().checkStateEffects(true);
        Assert.assertFalse(p.hasEnduringStory(), "precondition: story is off at two permanents");

        // The second Nori is the momentary third historic permanent.
        addCard("Nori, Teller of Tales", p);
        game.getAction().checkStateEffects(true);

        Assert.assertEquals(countCardsWithName(game, "Nori, Teller of Tales",
                ZoneType.Battlefield), 1, "legend rule should have removed the duplicate");
        Assert.assertTrue(p.hasEnduringStory(),
                "the duplicate should latch the story before the legend rule kills it");
    }

    /**
     * And it must STAY on once the board falls back under the threshold.
     *
     * Note the setup: the board is established and settled FIRST, then the duplicate arrives.
     * Materialising all three at once and checking once does NOT latch -- Ori's trigger is not a
     * registered active trigger until a state check has run, so the legend rule removes the
     * duplicate before anything is watching. That is an artifact of putting cards straight into
     * the zone, not something reachable in play (a permanent always arrives alone, into a board
     * whose triggers are already registered), but it is worth knowing before writing another of
     * these.
     */
    @Test
    public void theStoryStaysOnAfterTheDuplicateDies() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        addCard("Ori, Keeper of Songs", p);
        addCard("Nori, Teller of Tales", p);
        game.getAction().checkStateEffects(true);
        Assert.assertFalse(p.hasEnduringStory(), "precondition: story off at two permanents");

        addCard("Nori, Teller of Tales", p);
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(p.hasEnduringStory(), "precondition: latched by the duplicate");

        // Board is back to two historic permanents, below the threshold it was crossed at.
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(countCardsWithName(game, "Nori, Teller of Tales",
                ZoneType.Battlefield), 1, "still only one copy");
        Assert.assertTrue(p.hasEnduringStory(), "enduring story must not switch back off");
    }
}
