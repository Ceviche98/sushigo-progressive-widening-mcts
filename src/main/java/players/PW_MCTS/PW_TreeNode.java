package players.PW_MCTS;

import core.AbstractGameState;
import core.actions.AbstractAction;
import core.interfaces.IStateHeuristic;
import players.PlayerConstants;
import players.simple.RandomPlayer;
import utilities.ElapsedCpuTimer;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static players.PlayerConstants.*;
import static utilities.Utils.noise;

class PW_TreeNode {
    // Root node of tree
    PW_TreeNode root;
    // Parent of this node
    PW_TreeNode parent;
    // Children of this node
    Map<AbstractAction, PW_TreeNode> children = new HashMap<>();
    // Depth of this node
    final int depth;

    // Total value of this node
    private double totValue;
    // Number of visits
    private int nVisits;
    // Number of FM calls and State copies up until this node
    private int fmCallsCount;
    // Parameters guiding the search
    private PW_MCTSPlayer player;
    private Random rnd;
    private RandomPlayer randomPlayer = new RandomPlayer();

    // State in this node (closed loop)
    private AbstractGameState state;

    protected PW_TreeNode(PW_MCTSPlayer player, PW_TreeNode parent, AbstractGameState state, Random rnd) {
        this.player = player;
        this.fmCallsCount = 0;
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        totValue = 0.0;
        setState(state);
        if (parent != null) {
            depth = parent.depth + 1;
        } else {
            depth = 0;
        }
        this.rnd = rnd;
        randomPlayer.setForwardModel(player.getForwardModel());
    }

    /**
     * Performs full MCTS search, using the defined budget limits.
     */
    void mctsSearch() {

        PW_MCTSParams params = player.getParameters();

// --- ADD THESE LINES ---
        long totalExpansionTime = 0;//Extra
        long totalRolloutTime = 0;//Extra

        // Variables for tracking time budget
        double avgTimeTaken;
        double acumTimeTaken = 0;
        long remaining;
        int remainingLimit = params.breakMS;
        ElapsedCpuTimer elapsedTimer = new ElapsedCpuTimer();
        if (params.budgetType == BUDGET_TIME) {
            elapsedTimer.setMaxTimeMillis(params.budget);
        }

        // Tracking number of iterations for iteration budget
        int numIters = 0;

        boolean stop = false;

        while (!stop) {
            // New timer for this iteration
            ElapsedCpuTimer elapsedTimerIteration = new ElapsedCpuTimer();
            long startExpansion = System.nanoTime();//Extra
            // Selection + expansion: navigate tree until a node not fully expanded is found, add a new node to the tree
            PW_TreeNode selected = treePolicy();
            totalExpansionTime += (System.nanoTime() - startExpansion); //Extra
            // Monte carlo rollout: return value of MC rollout from the newly added node
            long startRollout = System.nanoTime();
            double delta = selected.rollOut();
            totalRolloutTime += (System.nanoTime() - startRollout);
            // Back up the value of the rollout through the tree
            selected.backUp(delta);
            // Finished iteration
            numIters++;

            // Check stopping condition
            PlayerConstants budgetType = params.budgetType;
            if (budgetType == BUDGET_TIME) {
                // Time budget
                acumTimeTaken += (elapsedTimerIteration.elapsedMillis());
                avgTimeTaken = acumTimeTaken / numIters;
                remaining = elapsedTimer.remainingTimeMillis();
                stop = remaining <= 2 * avgTimeTaken || remaining <= remainingLimit;
            } else if (budgetType == BUDGET_ITERATIONS) {
                // Iteration budget
                stop = numIters >= params.budget;
            } else if (budgetType == BUDGET_FM_CALLS) {
                // FM calls budget
                stop = fmCallsCount > params.budget;
            }
        }

        // --- ADD THIS AT THE END (outside the loop) ---
        //System.out.println("--- MCTSSearch Done ---");
        //System.out.println("Total Iterations: " + numIters);
        //System.out.println("Avg Expansion Time (ns): " + (totalExpansionTime / numIters));
        //System.out.println("Avg Rollout Time (ns):   " + (totalRolloutTime / numIters));
        //System.out.println("-------------------------");
// ---------------------------------------------
    }

    /**
     * Selection + expansion steps.
     * - Tree is traversed using UCB.
     * - If a node is visited, Progressive Widening is applied.
     * - A new child node is added IF k < C * N^alpha
     * - The child to add is chosen using the HEURISTIC.
     *
     * @return - new node added to the tree, or the node from which to rollout.
     */
    private PW_TreeNode treePolicy() {
        PW_TreeNode cur = this;
        PW_MCTSParams params = player.getParameters();

        while (cur.state.isNotTerminal() && cur.depth < params.maxTreeDepth) {

            List<AbstractAction> unexpanded = cur.unexpandedActions();
            int nExpandedChildren = cur.children.size() - unexpanded.size();

            // Calculate PW condition
            double pwLimit = params.progressiveWideningC * Math.pow(cur.nVisits, params.progressiveWideningAlpha);

            if (!unexpanded.isEmpty() && (nExpandedChildren < pwLimit || cur.nVisits == 0)) {
                // --- EXPAND ---
                // We are allowed to expand a new node
                // Use the heuristic to pick the best one
                cur = cur.guidedExpand(unexpanded);
                return cur;
            } else {
                // --- SELECT ---
                // We must select from existing children using UCB
                if (nExpandedChildren == 0) {
                    // This node has no actions, or PW prevents any expansion
                    // We are at a leaf for this simulation, so rollout from here
                    return cur;
                }
                AbstractAction actionChosen = cur.ucb();
                cur = cur.children.get(actionChosen);
            }
        }
        return cur;
    }


    private void setState(AbstractGameState newState) {
        state = newState;
        if (newState.isNotTerminal())
            for (AbstractAction action : player.getForwardModel().computeAvailableActions(state, player.getParameters().actionSpace)) {
                children.put(action, null); // mark a new node to be expanded
            }
    }

    /**
     * @return A list of the unexpanded Actions from this State
     */
    private List<AbstractAction> unexpandedActions() {
        return children.keySet().stream().filter(a -> children.get(a) == null).collect(toList());
    }

    /**
     * Expands the node by creating a new child node, CHOSEN BY THE HEURISTIC, and adding it to the tree.
     *
     * @param unexpandedActions - list of actions to evaluate
     * @return - new child node.
     */
    private PW_TreeNode guidedExpand(List<AbstractAction> unexpandedActions) {
        AbstractAction bestAction = null;
        double bestValue = -Double.MAX_VALUE;
        PW_MCTSParams params = player.getParameters();
        IStateHeuristic heuristic = params.getStateHeuristic();
        int playerID = player.getPlayerID();

        // We will store the state resulting from the best action
        AbstractGameState bestNextState = null;

        // 1. Evaluate all unexpanded actions using the heuristic
        for (AbstractAction action : unexpandedActions) {
            AbstractGameState nextState = state.copy();
            // We must use advance() to properly count FM calls for the heuristic evaluation
            advance(nextState, action.copy());

            double value = heuristic.evaluateState(nextState, playerID);
            value = noise(value, params.epsilon, player.getRnd().nextDouble());

            if (value > bestValue) {
                bestValue = value;
                bestAction = action;
                bestNextState = nextState;
            }
        }

        if (bestAction == null) {
            // Should not happen if unexpandedActions is not empty
            throw new AssertionError("No best action found during guided expansion.");
        }

        // 2. Create the new node for the best action
        // We use the already-computed state
        PW_TreeNode tn = new PW_TreeNode(player, this, bestNextState, rnd);
        children.put(bestAction, tn);
        return tn;
    }

    /**
     * Advance the current game state with the given action, count the FM call and compute the next available actions.
     *
     * @param gs  - current game state
     * @param act - action to apply
     */
    private void advance(AbstractGameState gs, AbstractAction act) {
        player.getForwardModel().next(gs, act);
        root.fmCallsCount++;
    }

    /**
     * Selects the best *expanded* child node using UCB.
     *
     * @return - The best action to take.
     */
    private AbstractAction ucb() {
        // Find child with highest UCB value
        AbstractAction bestAction = null;
        double bestValue = -Double.MAX_VALUE;
        PW_MCTSParams params = player.getParameters();

        for (AbstractAction action : children.keySet()) {
            PW_TreeNode child = children.get(action);
            if (child == null) {
                continue; // Skip unexpanded children
            }

            if (bestAction == null) {
                bestAction = action; // Initialize with the first valid child
            }

            // Find child value
            double hvVal = child.totValue;
            double childValue = hvVal / (child.nVisits + params.epsilon);

            // UCB exploration term
            double explorationTerm = params.K * Math.sqrt(Math.log(this.nVisits + 1) / (child.nVisits + params.epsilon));

            // Find 'UCB' value
            boolean iAmMoving = state.getCurrentPlayer() == player.getPlayerID();
            double uctValue = iAmMoving ? childValue : -childValue;
            uctValue += explorationTerm;

            // Apply small noise to break ties randomly
            uctValue = noise(uctValue, params.epsilon, player.getRnd().nextDouble());

            // Assign value
            if (uctValue > bestValue) {
                bestAction = action;
                bestValue = uctValue;
            }
        }

        if (bestAction == null)
            throw new AssertionError("UCB called but no expanded children found! This should be handled by treePolicy.");

        root.fmCallsCount++;  // log one iteration complete (for the selection)
        return bestAction;
    }

    /**
     * Perform a Monte Carlo rollout from this node.
     *
     * @return - value of rollout.
     */
    private double rollOut() {
        int rolloutDepth = 0; // counting from end of tree

        // If rollouts are enabled, select actions for the rollout in line with the rollout policy
        AbstractGameState rolloutState = state.copy();
        if (player.getParameters().rolloutLength > 0) {
            while (!finishRollout(rolloutState, rolloutDepth)) {
                AbstractAction next = randomPlayer.getAction(rolloutState, randomPlayer.getForwardModel().computeAvailableActions(rolloutState, randomPlayer.parameters.actionSpace));
                advance(rolloutState, next);
                rolloutDepth++;
            }
        }
        // Evaluate final state and return normalised score
        // ** NOTE: This still uses the heuristic if rolloutLength = 0 **
        // If you want *pure* random rollouts, you must ensure rolloutLength > 0
        // and the heuristic is only used for final state evaluation (or guided expansion).
        // The provided heuristic (V3) seems to be a state evaluator, so this is correct.
        double value = player.getParameters().getStateHeuristic().evaluateState(rolloutState, player.getPlayerID());
        if (Double.isNaN(value))
            throw new AssertionError("Illegal heuristic value - should be a number");
        return value;
    }

    /**
     * Checks if rollout is finished. Rollouts end on maximum length, or if game ended.
     *
     * @param rollerState - current state
     * @param depth       - current depth
     * @return - true if rollout finished, false otherwise
     */
    private boolean finishRollout(AbstractGameState rollerState, int depth) {
        if (depth >= player.getParameters().rolloutLength)
            return true;

        // End of game
        return !rollerState.isNotTerminal();
    }

    /**
     * Back up the value of the child through all parents. Increase number of visits and total value.
     *
     * @param result - value of rollout to backup
     */
    private void backUp(double result) {
        PW_TreeNode n = this;
        while (n != null) {
            n.nVisits++;
            n.totValue += result;
            n = n.parent;
        }
    }

    /**
     * Calculates the best action from the root according to the most visited node
     *
     * @return - the best AbstractAction
     */
    AbstractAction bestAction() {

        double bestValue = -Double.MAX_VALUE;
        AbstractAction bestAction = null;

        for (AbstractAction action : children.keySet()) {
            if (children.get(action) != null) {
                PW_TreeNode node = children.get(action);
                double childValue = node.nVisits;

                // Apply small noise to break ties randomly
                childValue = noise(childValue, player.getParameters().epsilon, player.getRnd().nextDouble());

                // Save best value (highest visit count)
                if (childValue > bestValue) {
                    bestValue = childValue;
                    bestAction = action;
                }
            }
        }

        if (bestAction == null) {
            // Can happen if no iterations were run or no actions were possible
            // Fallback to a random action
            List<AbstractAction> allActions = new ArrayList<>(children.keySet());
            if (allActions.isEmpty()) {
                throw new AssertionError("No actions available from root node.");
            }
            return allActions.get(rnd.nextInt(allActions.size()));
        }

        return bestAction;
    }
}
