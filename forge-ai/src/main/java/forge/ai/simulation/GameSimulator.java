package forge.ai.simulation;


import com.google.common.collect.Lists;

import forge.ai.AIOption;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.util.collect.FCollectionView;

import java.util.*;

public class GameSimulator {
    public static boolean COPY_STACK = false;
    final private SimulationController controller;
    private GameCopier copier;
    private Game simGame;
    private Player aiPlayer;
    private GameStateEvaluator eval;
    private List<String> origLines;
    private Score origScore;
    private SpellAbilityChoicesIterator interceptor;

    // Verifying that the copied game scores identically to the original costs a second full
    // evaluation (with debug string building enabled) for every simulator that gets constructed.
    // That's worth paying for in development and in the test suite, where a game copy bug should
    // fail loudly, but not in a shipped game where it only slows the AI down. Assertions are
    // enabled by Maven Surefire, so the check still runs for every test.
    private static final boolean CHECK_GAME_COPY_SCORE = areAssertionsEnabled();

    @SuppressWarnings("AssertWithSideEffects")
    private static boolean areAssertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    public GameSimulator(SimulationController controller, Game origGame, Player origAiPlayer, PhaseType advanceToPhase) {
        this.controller = controller;
        copier = new GameCopier(origGame);
        simGame = copier.makeCopy(advanceToPhase, origAiPlayer);

        aiPlayer = (Player) copier.find(origAiPlayer);
        eval = new GameStateEvaluator();

        origLines = new ArrayList<>();
        debugLines = origLines;

        debugPrint = false;
        origScore = eval.getScoreForGameState(origGame, origAiPlayer);

        if (advanceToPhase == null && CHECK_GAME_COPY_SCORE) {
            ensureGameCopyScoreMatches(origGame, origAiPlayer);
        }

        // If the stack on the original game is not empty, resolve it
        // first and get the updated eval score, since this is what we'll
        // want to compare to the eval score after simulating.
        if (COPY_STACK && !origGame.getStackZone().isEmpty()) {
            origLines = new ArrayList<>();
            debugLines = origLines;
            Game copyOrigGame = copier.makeCopy();
            Player copyOrigAiPlayer = copyOrigGame.getPlayers().get(1);
            resolveStack(copyOrigGame, copyOrigGame.getPlayers().get(0));
            origScore = eval.getScoreForGameState(copyOrigGame, copyOrigAiPlayer);
        }

        debugPrint = false;
        debugLines = null;
    }

    private void ensureGameCopyScoreMatches(Game origGame, Player origAiPlayer) {
        eval.setDebugging(true);
        List<String> simLines = new ArrayList<>();
        debugLines = simLines;
        Score simScore = eval.getScoreForGameState(simGame, aiPlayer);
        if (!simScore.equals(origScore)) {
            // Re-eval orig with debug printing.
            origLines = new ArrayList<>();
            debugLines = origLines;
            eval.getScoreForGameState(origGame, origAiPlayer);
            // Print debug info.
            printDiff(origLines, simLines);
            // make sure it gets printed
            System.out.flush();
            throw new RuntimeException("Game copy error. See diff output above for details.");
        }
        eval.setDebugging(false);
    }

    public void setInterceptor(SpellAbilityChoicesIterator interceptor) {
        this.interceptor = interceptor;
        ((PlayerControllerAi) aiPlayer.getController()).getAi().getSimulationPicker().setInterceptor(interceptor);
    }

    private void printDiff(List<String> lines1, List<String> lines2) {
        int i = 0;
        int j = 0;
        Collections.sort(lines1);
        Collections.sort(lines2);
        while (i < lines1.size() && j < lines2.size()) {
            String left = lines1.get(i);
            String right = lines2.get(j);
            int cmp = left.compareTo(right);
            if (cmp == 0) {
                i++; j++;
            } else if (cmp < 0) {
                System.out.println("-" + left);
                i++;
            } else { 
                System.out.println("+"  + right);
                j++;
            }
        }
        while (i < lines1.size()) {
            System.out.println("-" + lines1.get(i++));
        }
        while (j < lines2.size()) {
            System.out.println("+" + lines2.get(j++));
        }
    }

    public static boolean debugPrint;
    public static List<String> debugLines;
    public static void debugPrint(String str) {
        if (debugPrint) {
            System.out.println(str);
        }
        if (debugLines != null) {
            debugLines.add(str);
        }
    }

    private SpellAbility findSaInSimGame(final SpellAbility sa) {
        // is already an ability from sim game
        if (sa.getHostCard().getGame().equals(this.simGame)) {
            return sa;
        }
        Card origHostCard = sa.getHostCard();
        Card hostCard = (Card) copier.find(origHostCard);
        String desc = sa.getDescription();
        FCollectionView<SpellAbility> candidates = hostCard.getSpellAbilities();

        SpellAbility result = saMatcher(candidates, desc);
        for (SpellAbility cSa : candidates) {
            if (result != null) {
                break;
            }
            result = saMatcher(GameActionUtil.getAlternativeCosts(cSa, aiPlayer, true), desc);
        }

        // A MODE of a modal spell is not one of the host card's spell abilities and never can
        // be, so the matching above cannot succeed for one. Pinecone Strike is a single Charm
        // SpellAbility ("Choose one or both --") whose two modes live in its Choices list as
        // AbilitySubs; asking whether the card has an ability described "Pinecone Strike deals
        // 3 damage to target creature..." is asking the wrong question. The caller turns the
        // miss into Score(Integer.MIN_VALUE) -- the worst score an int can hold -- so every
        // modal spell was being judged unplayable rather than judged at all.
        //
        // Its mode is reachable because setAdditionalAbilityList() parents each choice to the
        // ability that owns it, so resolve the ROOT, which IS a top-level ability, and carry
        // the mode across as the chosen list. Only ever reached when the ordinary match has
        // already failed, so nothing that resolves today changes.
        if (result == null && sa.getParent() != null) {
            result = findModeInSimGame(sa, candidates);
        }

        if (result != null) {
            result = SpellAbilityChoiceCopier.copyCastChoices(sa, result, aiPlayer);
        }

        return result;
    }

    /** The sim game's copy of a modal spell, with `mode` selected. Null if it is not modal. */
    private SpellAbility findModeInSimGame(final SpellAbility mode,
            final FCollectionView<SpellAbility> candidates) {
        final SpellAbility root = mode.getRootAbility();
        if (root == mode) {
            return null;
        }
        final SpellAbility simRoot = saMatcher(candidates, root.getDescription());
        if (simRoot == null) {
            return null;
        }
        // Matched on SpellDescription rather than on position, exactly as
        // SpellAbilityChoiceCopier.copyChosenModes does: the choices are rebuilt in the copied
        // game and only their text is reliably the same object-to-object.
        for (final AbilitySub choice : simRoot.getAdditionalAbilityList("Choices")) {
            if (Objects.equals(choice.getParam("SpellDescription"),
                               mode.getParam("SpellDescription"))) {
                // Leave an already-chosen list alone; the caller knows better than we do.
                if (simRoot.getChosenList() == null) {
                    simRoot.setChosenList(Lists.newArrayList(choice));
                }
                return simRoot;
            }
        }
        return null;
    }

    private SpellAbility saMatcher(Iterable<SpellAbility> candidates, String desc) {
        // first pass for accuracy (spells with alternative costs)
        for (SpellAbility cSa : candidates) {
            if (desc.equals(cSa.getDescription())) {
                return cSa;
            }
        }
        // fall back for safety
        for (SpellAbility cSa : candidates) {
            if (desc.startsWith(cSa.getDescription())) {
                return cSa;
            }
        }
        return null;
    }

    public Score simulateSpellAbility(SpellAbility origSa) {
        return simulateSpellAbility(origSa, this.eval, true);
    }
    public Score simulateSpellAbility(SpellAbility origSa, boolean resolve) {
        return simulateSpellAbility(origSa, this.eval, resolve);
    }
    public Score simulateSpellAbility(SpellAbility origSa, GameStateEvaluator eval, boolean resolve) {
        SpellAbility sa;
        if (origSa.isLandAbility()) {
            Card hostCard = (Card) copier.find(origSa.getHostCard());
            if (origSa.canPlay()) {
                aiPlayer.playLand(hostCard, origSa);
            } else {
                System.err.println("Simulation: Couldn't play land! " + origSa);
            }
            sa = origSa;
        } else {
            // TODO: optimize: prune identical SA (e.g. two of the same card in hand)
            sa = findSaInSimGame(origSa);
            if (sa == null) {
                System.err.println("Simulation: SA not found! " + origSa + " / " + origSa.getClass());
                return new Score(Integer.MIN_VALUE);
            }

            debugPrint("Found SA " + sa + " on host card " + sa.getHostCard() + " with owner:"+ sa.getHostCard().getOwner());
            sa.setActivatingPlayer(aiPlayer);

            simGame.copyLastState();
            boolean success = ComputerUtil.handlePlayingSpellAbility(aiPlayer, sa, playingSa -> {
                if (interceptor != null) {
                    interceptor.announceX(playingSa);
                    interceptor.chooseTargets(playingSa, GameSimulator.this);
                } else {
                    SpellAbilityChoiceCopier.copyTargets(origSa, playingSa, copier::find);
                }
                if (debugPrint && !playingSa.getAllTargetChoices().isEmpty()) {
                    debugPrint("Targets: ");
                    for (TargetChoices target : playingSa.getAllTargetChoices()) {
                        System.out.print(target);
                    }
                    System.out.println();
                }
            });
            if (!success) {
                return new Score(Integer.MIN_VALUE);
            }
        }

        if (resolve) {
            // TODO: Support multiple opponents.
            Player opponent = aiPlayer.getWeakestOpponent();
            resolveStack(simGame, opponent);
        }

        // TODO: If this is during combat, before blockers are declared,
        // we should simulate how combat will resolve and evaluate that
        // state instead!
        List<String> simLines = null;
        if (debugPrint) {
            debugPrint("SimGame:");
            simLines = new ArrayList<>();
            debugLines = simLines;
            debugPrint = false;
        }
        Score score = eval.getScoreForGameState(simGame, aiPlayer);
        if (simLines != null) {
            debugLines = null;
            debugPrint = true;
            printDiff(origLines, simLines);
        }
        controller.possiblyCacheResult(score, origSa);
        if (controller.shouldRecurse() && !simGame.isGameOver()) {
            controller.push(sa, score, this);
            SpellAbilityPicker sim = new SpellAbilityPicker(aiPlayer);
            SpellAbility nextSa = sim.chooseSpellAbilityToPlay(controller);
            if (nextSa != null) {
                score = sim.getScoreForChosenAbility();
            }
            controller.pop(score, nextSa);
        }

        return score;
    }

    public static void resolveStack(final Game game, final Player opponent) {
        // TODO: This needs to set an AI controller for all opponents, in case of multiplayer.
        PlayerControllerAi sim = new PlayerControllerAi(game, opponent, opponent.getLobbyPlayer());
        sim.getAi().setUseSimulation(AIOption.USE_FULL_SIMULATION);
        opponent.runWithController(() -> {
            final Set<Card> allAffectedCards = new HashSet<>();
            game.getAction().checkStateEffects(false, allAffectedCards);
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty() && !game.isGameOver()) {
                debugPrint("Resolving:" + game.getStack().peekAbility());

                // Resolve the top effect on the stack.
                game.getStack().resolveStack();

                // Evaluate state based effects as a result of resolving stack.
                // Note: Needs to happen after resolve stack rather than at the
                // top of the loop to ensure state effects are evaluated after the
                // last resolved effect
                game.getAction().checkStateEffects(false, allAffectedCards);

                // Add any triggers additional triggers as a result of the above.
                // Must be below state effects, since legendary rule is evaluated
                // as part of state effects and trigger come afterward. (e.g. to
                // correctly handle two Dark Depths - one having no counters).
                game.getStack().addAllTriggeredAbilitiesToStack();

                // Continue until stack is empty.
            }
        }, sim);
    }

    public Game getSimulatedGameState() {
        return simGame;
    }

    public Score getScoreForOrigGame() {
        return origScore;
    }

    public GameCopier getGameCopier() {
        return copier;
    }
}
