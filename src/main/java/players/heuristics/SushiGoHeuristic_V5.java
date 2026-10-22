package players.heuristics;

import core.AbstractGameState;
import core.interfaces.IStateHeuristic;
import games.sushigo.SGGameState;
import games.sushigo.cards.SGCard.SGCardType;

/**
 * Implements a very simple, fast, static heuristic for Sushi Go!
 *
 * OBJETIVO: Ser extremadamente rápida usando valores fijos y
 * lógicas de turno simples, según la especificación del usuario.
 *
 * ESTRATEGIA:
 * 1. Usa la arquitectura `getGameScore() + Potencial`.
 * 2. El "potencial" se calcula con reglas estáticas simples.
 * 3. Wasabi: 4.2 hasta turno 3 (idx 2), luego 0.1
 * 4. Tempura: Valor estático (1.94) si hay 1 suelto.
 * 5. Sashimi: Valor estático (1.47 * N) por N cartas sueltas.
 * 6. Dumpling: Valor estático (0.25) si se está coleccionando (1-4).
 * 7. Maki: 0.6 * maki_count.
 * 8. Chopsticks: 1.5 hasta turno 5 (idx 4), luego 0.1
 * 9. Pudding: 1.6 * pudding_count (de toda la partida).
 */
public class SushiGoHeuristic_V5 implements IStateHeuristic {

    // --- Valores Estáticos Hard-coded ---
    private static final double WASABI_EARLY_VAL = 4.2;
    private static final double WASABI_LATE_VAL = 0.1;
    private static final double TEMPURA_VAL = 1.94; // Re-usado de V4
    private static final double SASHIMI_VAL = 1.47; // Re-usado de V4
    private static final double DUMPLING_VAL = 0.25;
    private static final double MAKI_MULT = 0.6;
    private static final double CHOPSTICKS_EARLY_VAL = 1.5;
    private static final double CHOPSTICKS_LATE_VAL = 0.1;
    private static final double PUDDING_VAL = 1.6;


    /**
     * Constructor (vacío).
     */
    public SushiGoHeuristic_V5() {
        // Constructor
    }

    /**
     * Main evaluation function.
     */
    @Override
    public double evaluateState(AbstractGameState gs, int playerId) {
        SGGameState state = (SGGameState) gs;

        // Si el juego ha terminado, devuelve el resultado real.
        if (!state.isNotTerminal()) {
            return state.getPlayerResults()[playerId].value * 100;
        }

        // --- Puntuación Actual + Puntuación Potencial (Simple) ---
        double totalHeuristicValue = state.getGameScore(playerId);

        // --- Sumar el valor POTENCIAL de las cartas en la mesa ---
        totalHeuristicValue += calculateWasabiScore(state, playerId);
        totalHeuristicValue += calculateTempuraScore(state, playerId);
        totalHeuristicValue += calculateSashimiScore(state, playerId);
        totalHeuristicValue += calculateMakiScore(state, playerId);
        totalHeuristicValue += calculatePuddingScore(state, playerId);
        totalHeuristicValue += calculateChopsticksScore(state, playerId);
        totalHeuristicValue += calculateDumplingScore(state, playerId);

        return totalHeuristicValue;
    }

    /**
     * Wasabi: 4.2 hasta turno 3 (idx 2), luego 0.1
     */
    private double calculateWasabiScore(SGGameState state, int playerId) {
        int openWasabiCount = state.getPlayedCardTypes(SGCardType.Wasabi, playerId).getValue();
        if (openWasabiCount == 0) return 0.0;

        int turn = state.getTurnCounter(); // 0-indexed

        // Turnos 1, 2, 3 (índice 0, 1, 2)
        if (turn <= 2) {
            return openWasabiCount * WASABI_EARLY_VAL;
        } else {
            return openWasabiCount * WASABI_LATE_VAL;
        }
    }

    /**
     * Tempura: Valor estático (1.94) si hay 1 suelto.
     */
    private double calculateTempuraScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Tempura, playerId).getValue();
        int myRemainder = myCount % 2; // 1 si es 1, 3, 5...

        if (myRemainder == 1) {
            return TEMPURA_VAL;
        }
        return 0.0;
    }

    /**
     * Sashimi: Valor estático (1.47 * N) por N cartas sueltas (1 o 2).
     */
    private double calculateSashimiScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Sashimi, playerId).getValue();
        int myRemainder = myCount % 3; // 1 o 2

        if (myRemainder > 0) {
            return myRemainder * SASHIMI_VAL;
        }
        return 0.0;
    }

    /**
     * Maki: 0.6 * maki_count.
     */
    private double calculateMakiScore(SGGameState state, int playerId) {
        int myMaki = state.getPlayedCardTypes(SGCardType.Maki, playerId).getValue();
        return myMaki * MAKI_MULT;
    }

    /**
     * Pudding: 1.6 * pudding_count (de toda la partida).
     */
    private double calculatePuddingScore(SGGameState state, int playerId) {
        // El pudín se puntúa al final del juego, así que miramos el contador de toda la partida
        int count = state.getPlayedCardTypesAllGame()[playerId].get(SGCardType.Pudding).getValue();
        return count * PUDDING_VAL;
    }

    /**
     * Chopsticks: 1.5 hasta turno 5 (idx 4), luego 0.1
     */
    private double calculateChopsticksScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Chopsticks, playerId).getValue();
        if (count == 0) return 0.0;

        int turn = state.getTurnCounter(); // 0-indexed

        // Turnos 1-5 (índice 0-4)
        if (turn <= 4) {
            return count * CHOPSTICKS_EARLY_VAL;
        } else { // Turno 6+
            return count * CHOPSTICKS_LATE_VAL;
        }
    }

    /**
     * Dumpling: Valor estático (0.25) si se está coleccionando (1-4).
     */
    private double calculateDumplingScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Dumpling, playerId).getValue();
        // Añade el pequeño bonus de potencial si la colección no está llena
        if (count > 0 && count < 5) {
            return DUMPLING_VAL; // Add 0.25
        }
        return 0.0;
    }


    // --- Métodos de la Interfaz IStateHeuristic ---

    @Override
    public double minValue() {
        return -50;
    }

    @Override
    public double maxValue() {
        return 200;
    }

    @Override
    public String toString() {
        return "SushiGoHeuristic_Simple_Static";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SushiGoHeuristic_V5;
    }

    @Override
    public int hashCode() {
        return 101; // Nuevo hashcode
    }
}
