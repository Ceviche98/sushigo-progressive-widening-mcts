package players.heuristics;

import core.AbstractGameState;
import core.interfaces.IStateHeuristic;
import core.components.Deck;
import games.sushigo.SGGameState;
import games.sushigo.cards.SGCard;
import games.sushigo.cards.SGCard.SGCardType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements a "Robust" heuristic for Sushi Go!
 *
 * This version uses a new design:
 * 1. Starts with the *actual* game score (`state.getGameScore()`), which includes
 * all points from scored Nigiri, Dumplings, and completed Tempura/Sashimi sets.
 * 2. Helper functions *only* add the *potential, unrealized* value of cards
 * on the board (e.g., open Wasabi, single Tempura, Maki, Pudding).
 *
 * This version adds dynamic recalculation of Base Weights each round based
 * on card availability (Hypergeometric Distribution) and player count.
 * V8: Implements correct "Perfect Information" logic (V8) based on *playable* cards pool.
 */
public class SushiGoHeuristic_V2 implements IStateHeuristic {

    // --- Dynamic Base Weights ---
    // Usa String keys para soportar pesos de sets múltiples (ej: "Tempura_1", "Tempura_2")
    private Map<String, Double> baseWeights = new HashMap<>();
    private int currentRound = -1; // Tracks the current round to know when to recalculate

    // --- Constants for Calculation ---
    // Initial counts of cards in a full 108-card deck
    private final Map<SGCardType, Integer> totalCardCounts = new HashMap<SGCardType, Integer>() {{
        put(SGCardType.Tempura, 14);
        put(SGCardType.Sashimi, 14);
        put(SGCardType.Dumpling, 14);
        put(SGCardType.Maki, 26); // (6*1 + 12*2 + 8*3) = 54 icons, but 26 cards
        put(SGCardType.SquidNigiri, 5);
        put(SGCardType.SalmonNigiri, 10);
        put(SGCardType.EggNigiri, 5);
        put(SGCardType.Pudding, 10);
        put(SGCardType.Wasabi, 6);
        put(SGCardType.Chopsticks, 4);
    }};
    private final int totalDeckSize = 108;

    // Tunable competition parameters from user prompt
    private final double P_COMP_SASHIMI = 0.6;
    private final double P_COMP_TEMPURA = 0.85;

    // Max PPC values from user prompt
    private final double MAX_PPC_SASHIMI = 10.0 / 3.0;
    private final double MAX_PPC_TEMPURA = 5.0 / 2.0;

    /**
     * Constructor: Initializes an empty map. Weights will be calculated
     * on the first call to evaluateState.
     */
    public SushiGoHeuristic_V2() {
        // Weights are now calculated dynamically.
    }

    /**
     * Main evaluation function.
     * Returns a heuristic score for the given game state for the specified player.
     */
    @Override
    public double evaluateState(AbstractGameState gs, int playerId) {
        SGGameState state = (SGGameState) gs;
        int round = state.getRoundCounter();

        // Check if the round has changed (or first run) and recalculate weights if it has
        if (round != currentRound || baseWeights.isEmpty()) {
            if (round == 0) {
                calculateInitialBaseWeights(state);
            } else {
                recalculateBaseWeights(state, round);
            }
            currentRound = round;
        }

        // If the game is over, return the actual game result.
        if (!state.isNotTerminal()) {
            return state.getPlayerResults()[playerId].value * 100; // Use win/loss value
        }

        // --- NEW LOGIC ---
        // Start with the *actual current score* (from Nigiri, Dumplings, completed sets).
        double totalHeuristicValue = state.getGameScore(playerId);

        // --- Add *POTENTIAL* (unrealized) value from cards on the board ---
        totalHeuristicValue += calculateWasabiScore(state, playerId);
        totalHeuristicValue += calculateTempuraScore(state, playerId);
        totalHeuristicValue += calculateSashimiScore(state, playerId);
        totalHeuristicValue += calculateMakiScore(state, playerId);
        totalHeuristicValue += calculatePuddingScore(state, playerId);
        totalHeuristicValue += calculateChopsticksScore(state, playerId);
        totalHeuristicValue += calculateDumplingScore(state, playerId); // Added per user request

        return totalHeuristicValue;
    }

    /**
     * Calculates the *initial* base weights for Round 1, based on player count.
     */
    private void calculateInitialBaseWeights(SGGameState state) {
        int nPlayers = state.getNPlayers();
        int n_sample_size = getCardsPerHand(nPlayers) * nPlayers; // Total cards in play this round
        int N = totalDeckSize; // Total population is the full deck

        // --- Calculate K (Total of specific card) ---
        int K_sashimi = totalCardCounts.get(SGCardType.Sashimi);
        int K_tempura = totalCardCounts.get(SGCardType.Tempura);
        int K_squid = totalCardCounts.get(SGCardType.SquidNigiri);
        int K_salmon = totalCardCounts.get(SGCardType.SalmonNigiri);

        // --- Calculate P(Availability) for Round 1 ---
        // Sashimi
        double pAvailSashimi_1 = 1.0 - (hyperGeo(N, K_sashimi, n_sample_size, 0) + hyperGeo(N, K_sashimi, n_sample_size, 1) + hyperGeo(N, K_sashimi, n_sample_size, 2)); // P(k>=3)
        double pAvailSashimi_2 = 1.0 - (hyperGeo(N, K_sashimi, n_sample_size, 0) + hyperGeo(N, K_sashimi, n_sample_size, 1) + hyperGeo(N, K_sashimi, n_sample_size, 2) +
                hyperGeo(N, K_sashimi, n_sample_size, 3) + hyperGeo(N, K_sashimi, n_sample_size, 4) + hyperGeo(N, K_sashimi, n_sample_size, 5)); // P(k>=6)
        // Tempura
        double pAvailTempura_1 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1)); // P(k>=2)
        double pAvailTempura_2 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1) + hyperGeo(N, K_tempura, n_sample_size, 2) + hyperGeo(N, K_tempura, n_sample_size, 3)); // P(k>=4)
        double pAvailTempura_3 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1) + hyperGeo(N, K_tempura, n_sample_size, 2) +
                hyperGeo(N, K_tempura, n_sample_size, 3) + hyperGeo(N, K_tempura, n_sample_size, 4) + hyperGeo(N, K_tempura, n_sample_size, 5)); // P(k>=6)
        // Nigiri for Wasabi
        double pAvailSquid = 1.0 - hyperGeo(N, K_squid, n_sample_size, 0); // P(k >= 1)
        double pAvailSalmon = 1.0 - hyperGeo(N, K_salmon, n_sample_size, 0); // P(k >= 1)

        // --- Set all Base Weights ---
        // Dynamic weights
        baseWeights.put("Sashimi_1", MAX_PPC_SASHIMI * pAvailSashimi_1 * P_COMP_SASHIMI);
        baseWeights.put("Sashimi_2", MAX_PPC_SASHIMI * pAvailSashimi_2 * P_COMP_SASHIMI);
        baseWeights.put("Tempura_1", MAX_PPC_TEMPURA * pAvailTempura_1 * P_COMP_TEMPURA);
        baseWeights.put("Tempura_2", MAX_PPC_TEMPURA * pAvailTempura_2 * P_COMP_TEMPURA);
        baseWeights.put("Tempura_3", MAX_PPC_TEMPURA * pAvailTempura_3 * P_COMP_TEMPURA);
        // Dynamic Wasabi EV: P(Squid) * 6 (added pts) + P(Salmon) * 4 (added pts)
        baseWeights.put("Wasabi", (pAvailSquid * 6.0) + (pAvailSalmon * 4.0));

        // Static weights from user prompt (for simple EV cards)
        baseWeights.put("SquidNigiri", 3.00);
        baseWeights.put("Maki", 1.00); // Base value per icon (only used for non-Maki calcs if needed)
        baseWeights.put("SalmonNigiri", 2.00);
        baseWeights.put("Pudding", 1.60);
        baseWeights.put("Chopsticks", 1.50);
        baseWeights.put("Dumpling", 0.25); // Set to 0.25 per user request
        baseWeights.put("EggNigiri", 0.0);  // Set to 0.0 per user request
    }


    /**
     * Recalculates the base weights at the start of a new round (R2 or R3).
     */
    private void recalculateBaseWeights(SGGameState state, int newRound) {
        int nPlayers = state.getNPlayers();
        int cardsPerHand = getCardsPerHand(nPlayers);
        int n_sample_size = cardsPerHand * nPlayers; // Total cards in play this round

        // --- Calculate N (Total cards remaining in deck) ---
        int cards_dealt_prev_rounds = (cardsPerHand * nPlayers) * newRound;
        int N = totalDeckSize - cards_dealt_prev_rounds;
        if (N <= 0) N = 1; // Avoid division by zero if deck is somehow empty

        // --- Calculate K (Total of specific card remaining) ---
        int totalPlayedSashimi = 0;
        int totalPlayedTempura = 0;
        int totalPlayedSquid = 0;
        int totalPlayedSalmon = 0;
        for (int i = 0; i < nPlayers; i++) {
            // Use getPlayedCardTypesAllGame to count cards from all previous rounds
            totalPlayedSashimi += state.getPlayedCardTypesAllGame()[i].get(SGCardType.Sashimi).getValue();
            totalPlayedTempura += state.getPlayedCardTypesAllGame()[i].get(SGCardType.Tempura).getValue();
            totalPlayedSquid += state.getPlayedCardTypesAllGame()[i].get(SGCardType.SquidNigiri).getValue();
            totalPlayedSalmon += state.getPlayedCardTypesAllGame()[i].get(SGCardType.SalmonNigiri).getValue();
        }
        int K_sashimi = totalCardCounts.get(SGCardType.Sashimi) - totalPlayedSashimi;
        int K_tempura = totalCardCounts.get(SGCardType.Tempura) - totalPlayedTempura;
        int K_squid = totalCardCounts.get(SGCardType.SquidNigiri) - totalPlayedSquid;
        int K_salmon = totalCardCounts.get(SGCardType.SalmonNigiri) - totalPlayedSalmon;

        // --- Calculate P(Availability) ---
        // Sashimi
        double pAvailSashimi_1 = 1.0 - (hyperGeo(N, K_sashimi, n_sample_size, 0) + hyperGeo(N, K_sashimi, n_sample_size, 1) + hyperGeo(N, K_sashimi, n_sample_size, 2)); // P(k>=3)
        double pAvailSashimi_2 = 1.0 - (hyperGeo(N, K_sashimi, n_sample_size, 0) + hyperGeo(N, K_sashimi, n_sample_size, 1) + hyperGeo(N, K_sashimi, n_sample_size, 2) +
                hyperGeo(N, K_sashimi, n_sample_size, 3) + hyperGeo(N, K_sashimi, n_sample_size, 4) + hyperGeo(N, K_sashimi, n_sample_size, 5)); // P(k>=6)
        // Tempura
        double pAvailTempura_1 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1)); // P(k>=2)
        double pAvailTempura_2 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1) + hyperGeo(N, K_tempura, n_sample_size, 2) + hyperGeo(N, K_tempura, n_sample_size, 3)); // P(k>=4)
        double pAvailTempura_3 = 1.0 - (hyperGeo(N, K_tempura, n_sample_size, 0) + hyperGeo(N, K_tempura, n_sample_size, 1) + hyperGeo(N, K_tempura, n_sample_size, 2) +
                hyperGeo(N, K_tempura, n_sample_size, 3) + hyperGeo(N, K_tempura, n_sample_size, 4) + hyperGeo(N, K_tempura, n_sample_size, 5)); // P(k>=6)
        // Nigiri for Wasabi
        double pAvailSquid = 1.0 - hyperGeo(N, K_squid, n_sample_size, 0); // P(k >= 1)
        double pAvailSalmon = 1.0 - hyperGeo(N, K_salmon, n_sample_size, 0); // P(k >= 1)

        // --- Calculate and store new Base Weight ---
        baseWeights.put("Sashimi_1", MAX_PPC_SASHIMI * pAvailSashimi_1 * P_COMP_SASHIMI);
        baseWeights.put("Sashimi_2", MAX_PPC_SASHIMI * pAvailSashimi_2 * P_COMP_SASHIMI);
        baseWeights.put("Tempura_1", MAX_PPC_TEMPURA * pAvailTempura_1 * P_COMP_TEMPURA);
        baseWeights.put("Tempura_2", MAX_PPC_TEMPURA * pAvailTempura_2 * P_COMP_TEMPURA);
        baseWeights.put("Tempura_3", MAX_PPC_TEMPURA * pAvailTempura_3 * P_COMP_TEMPURA);
        // Dynamic Wasabi EV
        baseWeights.put("Wasabi", (pAvailSquid * 6.0) + (pAvailSalmon * 4.0));
    }

    /**
     * Helper to get the number of cards dealt per hand based on player count.
     */
    private int getCardsPerHand(int nPlayers) {
        switch (nPlayers) {
            case 2: return 10;
            case 3: return 9;
            case 4: return 8;
            case 5: return 7;
            default: return 9; // Default to 3-player
        }
    }

    /**
     * Helper to get the turn index on which perfect information is achieved.
     * (e.g., in 3-player, hands are 9 cards. Turn 0, 1, 2. By turn 2, you've seen 3 hands)
     * (e.g., in 4-player, hands are 8 cards. Turn 0, 1, 2, 3. By turn 3, you've seen 4 hands)
     */
    private int getPerfectInfoTurn(int nPlayers) {
        // We have perfect info once we've seen nPlayers hands.
        // This happens on turn index (nPlayers - 1).
        return nPlayers - 1;
    }

    /**
     * Calculates the heuristic value for *open* Wasabi cards.
     * Switches to "perfect information" logic after seeing all hands.
     */
    private double calculateWasabiScore(SGGameState state, int playerId) {
        int openWasabiCount = state.getPlayedCardTypes(SGCardType.Wasabi, playerId).getValue();
        if (openWasabiCount == 0) return 0.0;

        int turn = state.getTurnCounter();
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística ---
            if (turn > 3) { // Reduced from 4, as turns 4+ are peak chopsticks
                return openWasabiCount * 0.1; // Decayed value
            } else {
                return openWasabiCount * baseWeights.getOrDefault("Wasabi", 0.0); // Base Weight
            }
        } else {
            // --- Fase de Información Perfecta (Lógica V8) ---
            // Contar Nigiris jugables (en cualquier mano)
            int highValueNigirisInHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.SquidNigiri || card.type == SGCardType.SalmonNigiri) {
                        highValueNigirisInHands++;
                    }
                }
            }

            // El "pool jugable" es la cantidad de Nigiris en las manos.
            // Necesitamos al menos 1 para cubrir un Wasabi.
            if (highValueNigirisInHands >= openWasabiCount) {
                // Damos el valor del mejor combo posible (Squid)
                return openWasabiCount * baseWeights.getOrDefault("Wasabi", 6.0);
            } else if (highValueNigirisInHands > 0) {
                // Hay Nigiris, pero no suficientes para todos mis Wasabis
                // Valorar los que se pueden completar
                return highValueNigirisInHands * baseWeights.getOrDefault("Wasabi", 6.0) +
                        (openWasabiCount - highValueNigirisInHands) * 0.1; // Valor perdido
            } else {
                return 0.1 * openWasabiCount; // Carta perdida
            }
        }
    }

    /**
     * Calculates the heuristic value for *incomplete* Tempura sets.
     * Switches to "perfect information" logic after seeing all hands.
     */
    private double calculateTempuraScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Tempura, playerId).getValue();
        int myRemainder = myCount % 2; // Solo valorar la carta suelta
        if (myRemainder == 0) return 0.0;

        int turn = state.getTurnCounter();
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());
        int numCompletedSets = myCount / 2;

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística ---
            switch (numCompletedSets) {
                case 0: // Has 1, needs 2nd (Set 1)
                    return baseWeights.getOrDefault("Tempura_1", 0.0);
                case 1: // Has 3, needs 4th (Set 2)
                    return baseWeights.getOrDefault("Tempura_2", 0.0);
                case 2: // Has 5, needs 6th (Set 3)
                    return baseWeights.getOrDefault("Tempura_3", 0.0);
                default:
                    return baseWeights.getOrDefault("Tempura_3", 0.0);
            }
        } else {
            // --- Fase de Información Perfecta (Lógica V8) ---
            // Contar el "pool jugable": mi resto + todas las manos
            int tempurasInAllHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.Tempura) tempurasInAllHands++;
                }
            }
            int totalPlayablePool = myRemainder + tempurasInAllHands;
            int neededForSet = 2; // Siempre necesitamos 2 para un set

            if (totalPlayablePool >= neededForSet) {
                // ¡Aún es posible completar otro set!
                // Usamos el mejor peso base como "valor potencial"
                return baseWeights.getOrDefault("Tempura_1", MAX_PPC_TEMPURA);
            } else {
                // Es imposible, esta es una carta perdida
                return 0.1;
            }
        }
    }

    /**
     * Calculates the heuristic value for *incomplete* Sashimi sets.
     * Switches to "perfect information" logic after seeing all hands.
     */
    private double calculateSashimiScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Sashimi, playerId).getValue();
        int myRemainder = myCount % 3; // Solo valorar las 1 o 2 sueltas
        if (myRemainder == 0) return 0.0;

        int turn = state.getTurnCounter();
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());
        int numCompletedSets = myCount / 3;

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística ---
            double potential;
            switch (numCompletedSets) {
                case 0: // Has 1 or 2, needs 3rd (Set 1)
                    potential = baseWeights.getOrDefault("Sashimi_1", 0.0);
                    break;
                case 1: // Has 4 or 5, needs 6th (Set 2)
                    potential = baseWeights.getOrDefault("Sashimi_2", 0.0);
                    break;
                default: // Has 7+, potential for 3rd+ set
                    potential = baseWeights.getOrDefault("Sashimi_2", 0.0);
            }
            if (myRemainder == 1) {
                return potential; // Value for the first card of the set
            } else { // remainder == 2
                return potential * 1.5; // "Commitment bonus" for the second card
            }

        } else {
            // --- Fase de Información Perfecta (Lógica V8) ---
            // Contar el "pool jugable": mi resto + todas las manos
            int sashimisInAllHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.Sashimi) sashimisInAllHands++;
                }
            }
            int totalPlayablePool = myRemainder + sashimisInAllHands;
            int neededForSet = 3; // Siempre necesitamos 3 para un set

            if (totalPlayablePool >= neededForSet) {
                // ¡Aún es posible completar este set!
                double potential = baseWeights.getOrDefault("Sashimi_1", MAX_PPC_SASHIMI);
                if (myRemainder == 1) {
                    return potential;
                } else { // remainder == 2
                    return potential * 1.5;
                }
            } else {
                // Es imposible, esta es una carta perdida
                return 0.1;
            }
        }
    }

    /**
     * Calculates the *potential* score from Maki rolls.
     * Uses simple base weight for first 2 turns (unreliable info).
     * Uses "Pure relative value" logic after Turn 2.
     * NOTE: getGameScore() does NOT include Maki points; they are onRoundEnd.
     * This function is NECESSARY.
     */
    private double calculateMakiScore(SGGameState state, int playerId) {
        // SGGameState does not differentiate between Maki-1, 2, or 3 cards.
        // It only provides the total *icon* count.
        int myMaki = state.getPlayedCardTypes(SGCardType.Maki, playerId).getValue();
        if (myMaki == 0) return 0.0;

        int turn = state.getTurnCounter();

        // --- Point 2: Maki Logic ---
        if (turn < 2) {
            // Turns 0 & 1: Info is unreliable. Use a simple base weight per icon
            // approximating the user's request.
            switch (myMaki) {
                case 1: return 0.5;  // Low value (per user request)
                case 2: return 1.4;  // Medium value (per user request)
                case 3: return 2.8;  // High value (per user request, < 3.0)
                default: return 2.8 + (myMaki - 3) * 0.5; // Diminishing returns for extras
            }
        }

        // Turn 2 onwards: Use relative logic, as info is more reliable
        int nPlayers = state.getNPlayers();
        List<Integer> opponentMakis = new ArrayList<>();
        int maxOpponentMaki = 0;
        int secondMaxOpponentMaki = 0;
        int nFirstPlaceTies = 0;
        int nSecondPlaceTies = 0;
        boolean amFirst = true;

        for (int i = 0; i < nPlayers; i++) {
            if (i == playerId) continue;
            int oppMaki = state.getPlayedCardTypes(SGCardType.Maki, i).getValue();
            opponentMakis.add(oppMaki);

            if (oppMaki > myMaki) amFirst = false;
            // Count ties *only* if we are actually tied (myMaki > 0)
            if (oppMaki == myMaki && myMaki > 0) nFirstPlaceTies++;

            if (oppMaki > maxOpponentMaki) {
                secondMaxOpponentMaki = maxOpponentMaki;
                maxOpponentMaki = oppMaki;
            } else if (oppMaki > secondMaxOpponentMaki) {
                secondMaxOpponentMaki = oppMaki;
            }
        }

        if (amFirst) {
            return 6.0 / (nFirstPlaceTies + 1); // +1 for me
        }

        // I am not in first place. Check if I am in second.
        if (myMaki < maxOpponentMaki) {
            // Check for second place (only if 3+ players)
            if (nPlayers > 2 && myMaki > secondMaxOpponentMaki) {
                return 3.0; // Alone in second
            } else if (nPlayers > 2 && myMaki == secondMaxOpponentMaki) {
                // I am tied for second. Count how many.
                for (int oppMaki : opponentMakis) {
                    if (oppMaki == myMaki) nSecondPlaceTies++;
                }
                return 3.0 / (nSecondPlaceTies + 1); // +1 for me
            }
        }
        return 0.0; // Not in first or second
    }

    /**
     * Calculates the *potential* score from Pudding at the end of the *game*.
     */
    private double calculatePuddingScore(SGGameState state, int playerId) {
        // Pudding is counted across all rounds, so use getPlayedCardTypesAllGame
        int count = state.getPlayedCardTypesAllGame()[playerId].get(SGCardType.Pudding).getValue();
        if (count == 0) return 0.0;

        int round = state.getRoundCounter();

        if (round < 2) { // Round 0 or 1
            return count * baseWeights.getOrDefault("Pudding", 1.60);
        } else { // Final round (2)
            double myScore = state.getGameScore(playerId);
            double maxOppScore = 0.0;
            for (int i = 0; i < state.getNPlayers(); i++) {
                if (i == playerId) continue;
                maxOppScore = Math.max(maxOppScore, state.getGameScore(i));
            }
            if (myScore < maxOppScore) {
                return count * 6.0; // High value if losing
            } else {
                return count * 0.5; // Low value if winning
            }
        }
    }

    /**
     * Calculates the *potential* value of Chopsticks.
     * Logic to "check_hand_for_combos" is NOT implemented, as this
     * heuristic runs on a determinized (faked) state. Evaluating a
     * faked hand would lead to unreliable/incorrect decisions.
     */
    private double calculateChopsticksScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Chopsticks, playerId).getValue();
        if (count == 0) return 0.0;

        int turn = state.getTurnCounter(); // 0-indexed

        // Per user request: Boost value in turns 3, 4, 5 (index 2, 3, 4)
        if (turn >= 2 && turn <= 4) { // Turns 3, 4, 5 (prime time)
            return count * (baseWeights.getOrDefault("Chopsticks", 1.50) * 1.5); // 50% bonus
        } else if (turn > 4) { // Turn 6 onwards
            return count * 0.1; // Decay value
        } else { // Turns 1, 2 (index 0, 1)
            return count * baseWeights.getOrDefault("Chopsticks", 1.50); // Normal
        }
    }

    /**
     * Calculates the small "potential" value for collecting Dumplings.
     * The actual 1, 3, 6, 10, 15 points are already in getGameScore().
     */
    private double calculateDumplingScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Dumpling, playerId).getValue();
        // This adds a small "potential to collect more" bonus, as requested.
        if (count > 0 && count < 5) {
            return baseWeights.getOrDefault("Dumpling", 0.25); // Add 0.25
        }
        return 0.0;
    }


    // --- Helper Functions for Hypergeometric Distribution ---

    /**
     * Hypergeometric Distribution PMF (Probability Mass Function).
     * P(k) = [C(K, k) * C(N-K, n-k)] / C(N, n)
     *
     * @param N Total items in population (e.g., 81 cards in deck)
     * @param K Total successes in population (e.g., 12 Sashimi left)
     * @param n Sample size (e.g., 27 cards in round)
     * @param k Successes in sample (e.g., 3 Sashimi)
     * @return probability of getting exactly k successes
     */
    private double hyperGeo(int N, int K, int n, int k) {
        if (k < 0 || k > K || k > n) return 0.0;
        // Cleaned: "n - k < 0" is redundant
        if (N - K < 0 || n - k > N - K) return 0.0; // More failures in sample than in population

        long c1 = combinations(K, k);
        long c2 = combinations(N - K, n - k);
        long c3 = combinations(N, n);

        if (c1 == -1 || c2 == -1 || c3 == -1 || c3 == 0) {
            // Overflow or division by zero, return 0 as a safe fallback
            // This can happen if N, K, or n are very large or 0
            return 0.0;
        }

        return (double) (c1 * c2) / c3;
    }

    /**
     * Calculates combinations C(n, k) = n! / (k! * (n-k)!)
     * Includes checks for overflow.
     * @return C(n, k) or -1 if overflow occurs
     */
    private long combinations(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        if (k == 0 || k == n) {
            return 1;
        }
        if (k > n / 2) {
            k = n - k;
        }
        long res = 1;
        for (int i = 1; i <= k; ++i) {
            // Check for potential overflow before multiplication
            if (res > Long.MAX_VALUE / (n - i + 1)) {
                return -1; // Overflow
            }
            res = res * (n - i + 1);
            if (res < 0) {
                return -1; // Overflow
            }
            res = res / i;
        }
        return res;
    }


    // --- IStateHeuristic Interface Methods ---
    @Override
    public double minValue() {
        return -50; // Min possible score (losing all pudding)
    }

    @Override
    public double maxValue() {
        return 200; // Realistic max score
    }

    @Override
    public String toString() {
        return "SushiGoHeuristic[RobustV8_CorrectPerfectInfo]";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SushiGoHeuristic_V2;
    }

    @Override
    public int hashCode() {
        return 78; // Changed hashcode
    }
}
