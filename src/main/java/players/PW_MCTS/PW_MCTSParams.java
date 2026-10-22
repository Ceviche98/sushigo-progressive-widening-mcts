package players.PW_MCTS;

import core.AbstractGameState;
import core.interfaces.IStateHeuristic;
import players.PlayerParameters;

import java.util.Arrays;

/**
 * Parameters for Progressive Widening MCTS.
 * Adds:
 * - progressiveWideningC: The constant C for PW (k <= C * N^alpha)
 * - progressiveWideningAlpha: The exponent alpha for PW
 */
public class PW_MCTSParams extends PlayerParameters {

    public double K = Math.sqrt(2);
    public int rolloutLength = 10;
    public int maxTreeDepth = 100;
    public double epsilon = 1e-6;
    public IStateHeuristic heuristic = AbstractGameState::getHeuristicScore;

    // Progressive Widening parameters
    public double progressiveWideningC = 1.0;
    public double progressiveWideningAlpha = 0.5;

    public PW_MCTSParams() {
        addTunableParameter("K", Math.sqrt(2), Arrays.asList(0.0, 0.1, 1.0, Math.sqrt(2), 3.0, 10.0));
        addTunableParameter("rolloutLength", 10, Arrays.asList(0, 3, 10, 30, 100));
        addTunableParameter("maxTreeDepth", 100, Arrays.asList(1, 3, 10, 30, 100));
        addTunableParameter("epsilon", 1e-6);
        addTunableParameter("heuristic", (IStateHeuristic) AbstractGameState::getHeuristicScore);

        // PW Params
        addTunableParameter("progressiveWideningC", 1.0, Arrays.asList(0.0, 0.5, 1.0, 2.0, 5.0));
        addTunableParameter("progressiveWideningAlpha", 0.5, Arrays.asList(0.1, 0.25, 0.5, 0.75, 1.0));
    }

    @Override
    public void _reset() {
        super._reset();
        K = (double) getParameterValue("K");
        rolloutLength = (int) getParameterValue("rolloutLength");
        maxTreeDepth = (int) getParameterValue("maxTreeDepth");
        epsilon = (double) getParameterValue("epsilon");
        heuristic = (IStateHeuristic) getParameterValue("heuristic");

        // PW Params
        progressiveWideningC = (double) getParameterValue("progressiveWideningC");
        progressiveWideningAlpha = (double) getParameterValue("progressiveWideningAlpha");
    }

    @Override
    protected PW_MCTSParams _copy() {
        // All the copying is done in TunableParameters.copy()
        return new PW_MCTSParams();
    }

    @Override
    public IStateHeuristic getStateHeuristic() {
        return heuristic;
    }

    @Override
    public PW_MCTSPlayer instantiate() {
        return new PW_MCTSPlayer((PW_MCTSParams) this.copy());
    }
}
