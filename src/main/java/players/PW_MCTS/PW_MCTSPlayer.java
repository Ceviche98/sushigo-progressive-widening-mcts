package players.PW_MCTS;

import core.AbstractGameState;
import core.AbstractPlayer;
import core.actions.AbstractAction;
import core.interfaces.IStateHeuristic;

import java.util.List;
import java.util.Random;

/**
 * This is a Progressive Widening MCTS player.
 * It uses the new PWMCTSParams and PWTreeNode.
 */
public class PW_MCTSPlayer extends AbstractPlayer {

    public PW_MCTSPlayer() {
        this(System.currentTimeMillis());
    }

    public PW_MCTSPlayer(long seed) {
        super(new PW_MCTSParams(), "Progressive Widening MCTS");
        parameters.setRandomSeed(seed);
        rnd = new Random(seed);

        // These parameters can be changed
        PW_MCTSParams params = getParameters();
        params.K = Math.sqrt(2);
        params.rolloutLength = 10;
        params.maxTreeDepth = 10; // Reduced default depth for PW
        params.epsilon = 1e-6;
        params.progressiveWideningC = 1.0;
        params.progressiveWideningAlpha = 0.5;
    }

    public PW_MCTSPlayer(PW_MCTSParams params) {
        super(params, "Progressive Widening MCTS");
        rnd = new Random(params.getRandomSeed());
    }

    @Override
    public AbstractAction _getAction(AbstractGameState gameState, List<AbstractAction> actions) {
        // Search for best action from the root
        PW_TreeNode root = new PW_TreeNode(this, null, gameState, rnd);

        // mctsSearch does all of the hard work
        root.mctsSearch();

        // Return best action
        return root.bestAction();
    }

    @Override
    public PW_MCTSParams getParameters() {
        return (PW_MCTSParams) parameters;
    }

    public void setStateHeuristic(IStateHeuristic heuristic) {
        getParameters().heuristic = heuristic;
    }

    @Override
    public String toString() {
        return super.toString();
    }

    @Override
    public PW_MCTSPlayer copy() {
        return new PW_MCTSPlayer((PW_MCTSParams) parameters.copy());
    }
}
