package players.heuristics;

import core.AbstractGameState;
import core.interfaces.IStateHeuristic;
// Importaciones necesarias para la lógica de "info perfecta"
import core.components.Deck;
import games.sushigo.cards.SGCard;
// Se asume que SGGameState y SGCardType están en estos paquetes
import games.sushigo.SGGameState;
import games.sushigo.cards.SGCard.SGCardType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements a "Hybrid-Fast" heuristic for Sushi Go!
 *
 * OBJETIVO: Ser extremadamente rápida, pero recuperar la lógica
 * más importante de V8: el "conteo perfecto" al final de la ronda.
 *
 * ESTRATEGIA:
 * 1. Usa la arquitectura `getGameScore() + Potencial`.
 * 2. ELIMINA todos los cálculos de probabilidad (hyperGeo).
 * 3. USA pesos base fijos (hard-coded).
 * 4. MANTIENE las lógicas "rápidas": Dumpling, Chopsticks.
 * 5. MANTIENE las lógicas de "costo medio": Maki relativo, Pudding.
 * 6. AÑADE DE NUEVO la lógica de "Información Perfecta" (V8)
 * para Tempura, Sashimi y Wasabi, ya que su costo (O(N*K))
 * es mucho menor que hyperGeo.
 * 7. (V9) AÑADE bonus por "negación" (denial) en la fase de info perfecta.
 */
public class SushiGoHeuristic_V4 implements IStateHeuristic {

    // --- Pesos Base Estáticos (Hard-coded) ---
    private static final Map<SGCardType, Double> baseWeights = new HashMap<>();
    static {
        baseWeights.put(SGCardType.Tempura, 1.94);
        baseWeights.put(SGCardType.Sashimi, 1.47);
        baseWeights.put(SGCardType.Dumpling, 0.25); // Bonus de potencial
        baseWeights.put(SGCardType.Maki, 1.00);     // Usado solo en el switch de Maki
        baseWeights.put(SGCardType.SquidNigiri, 3.00);
        baseWeights.put(SGCardType.SalmonNigiri, 2.00);
        baseWeights.put(SGCardType.EggNigiri, 0.0);   // Sin potencial
        baseWeights.put(SGCardType.Pudding, 1.60);
        baseWeights.put(SGCardType.Wasabi, 4.20);
        baseWeights.put(SGCardType.Chopsticks, 1.50);
    }

    /**
     * Constructor (vacío, todo es estático).
     */
    public SushiGoHeuristic_V4() {
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

        // --- Arquitectura V8: Puntuación Actual + Puntuación Potencial ---
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
     * Helper to get the turn index on which perfect information is achieved.
     */
    private int getPerfectInfoTurn(int nPlayers) {
        // We have perfect info once we've seen nPlayers hands.
        // This happens on turn index (nPlayers - 1).
        return nPlayers - 1;
    }

    /**
     * Calcula el valor de los Wasabis abiertos (sin usar).
     * Rápido: solo comprueba el contador y el turno.
     */
    private double calculateWasabiScore(SGGameState state, int playerId) {
        int openWasabiCount = state.getPlayedCardTypes(SGCardType.Wasabi, playerId).getValue();
        if (openWasabiCount == 0) return 0.0;

        int turn = state.getTurnCounter(); // 0-indexed
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística (Rápida) ---
            if (turn > 3) { // Decae después del turno 4
                return openWasabiCount * 0.1;
            } else {
                return openWasabiCount * baseWeights.get(SGCardType.Wasabi);
            }
        } else {
            // --- Fase de Información Perfecta (Costo Medio) ---
            int highValueNigirisInHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.SquidNigiri || card.type == SGCardType.SalmonNigiri) {
                        highValueNigirisInHands++;
                    }
                }
            }
            if (highValueNigirisInHands >= openWasabiCount) {
                return openWasabiCount * baseWeights.get(SGCardType.Wasabi); // Valor completo
            } else if (highValueNigirisInHands > 0) {
                return highValueNigirisInHands * baseWeights.get(SGCardType.Wasabi) +
                        (openWasabiCount - highValueNigirisInHands) * 0.1; // Valor parcial
            } else {
                return 0.1 * openWasabiCount; // Carta perdida
            }
        }
    }

    /**
     * Calcula el valor de los Tempuras sueltos.
     * Rápido: solo aritmética.
     */
    private double calculateTempuraScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Tempura, playerId).getValue();
        int myRemainder = myCount % 2; // 1 si es 1, 3, 5...
        // Si no tengo cartas sueltas, mi potencial es 0 (no necesito calcular negación)
        if (myRemainder == 0) return 0.0;

        int turn = state.getTurnCounter();
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística (Rápida) ---
            return myRemainder * baseWeights.get(SGCardType.Tempura);
        } else {
            // --- Fase de Información Perfecta (Costo Medio) ---
            int tempurasInAllHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.Tempura) tempurasInAllHands++;
                }
            }
            int totalPlayablePool = myRemainder + tempurasInAllHands;
            int neededForSet = 2;

            // 1. Calcular mi potencial
            double myPotential;
            if (totalPlayablePool >= neededForSet) {
                myPotential = baseWeights.get(SGCardType.Tempura); // ¡Posible!
            } else {
                myPotential = 0.1; // Carta perdida
            }

            // 2. Calcular Bonus por Negación (NUEVO)
            double denialBonus = 0.0;
            for (int i = 0; i < state.getNPlayers(); i++) {
                if (i == playerId) continue;
                int oppRemainder = state.getPlayedCardTypes(SGCardType.Tempura, i).getValue() % 2;
                if (oppRemainder == 1) { // Oponente necesitaba uno
                    int oppPlayablePool = oppRemainder + tempurasInAllHands;
                    if (oppPlayablePool < neededForSet) {
                        // Mi jugada (que resultó en este estado) les negó el set
                        denialBonus += 5.0; // +5 puntos por negación
                    }
                }
            }
            return myPotential + denialBonus;
        }
    }

    /**
     * Calcula el valor de los Sashimis sueltos.
     * Rápido: solo aritmética y un 'if'.
     */
    private double calculateSashimiScore(SGGameState state, int playerId) {
        int myCount = state.getPlayedCardTypes(SGCardType.Sashimi, playerId).getValue();
        int myRemainder = myCount % 3; // 1 o 2
        // Si no tengo cartas sueltas, mi potencial es 0 (no necesito calcular negación)
        if (myRemainder == 0) return 0.0;

        int turn = state.getTurnCounter();
        int perfectInfoTurn = getPerfectInfoTurn(state.getNPlayers());

        double base = baseWeights.get(SGCardType.Sashimi);
        double potentialValue = (myRemainder == 1) ? base : (base + (base * 1.5));

        if (turn < perfectInfoTurn) {
            // --- Fase Probabilística (Rápida) ---
            return potentialValue;
        } else {
            // --- Fase de Información Perfecta (Costo Medio) ---
            int sashimisInAllHands = 0;
            for (Deck<SGCard> hand : state.getPlayerHands()) {
                for (SGCard card : hand.getComponents()) {
                    if (card.type == SGCardType.Sashimi) sashimisInAllHands++;
                }
            }
            int totalPlayablePool = myRemainder + sashimisInAllHands;
            int neededForSet = 3;

            // 1. Calcular mi potencial
            double myPotential;
            if (totalPlayablePool >= neededForSet) {
                myPotential = potentialValue; // ¡Posible!
            } else {
                myPotential = 0.1; // Carta perdida
            }

            // 2. Calcular Bonus por Negación (NUEVO)
            double denialBonus = 0.0;
            for (int i = 0; i < state.getNPlayers(); i++) {
                if (i == playerId) continue;
                int oppRemainder = state.getPlayedCardTypes(SGCardType.Sashimi, i).getValue() % 3;
                if (oppRemainder == 2) { // Oponente necesitaba uno
                    int oppPlayablePool = oppRemainder + sashimisInAllHands;
                    if (oppPlayablePool < neededForSet) {
                        // Mi jugada les negó el set de 10 puntos
                        denialBonus += 10.0; // +10 puntos por negación
                    }
                }
            }
            return myPotential + denialBonus;
        }
    }

    /**
     * Calcula el valor *potencial* de los Makis.
     * Costo Medio: Un bucle de N_JUGADORES.
     * Conservado porque es una lógica de alto valor.
     */
    private double calculateMakiScore(SGGameState state, int playerId) {
        int myMaki = state.getPlayedCardTypes(SGCardType.Maki, playerId).getValue();
        if (myMaki == 0) return 0.0;

        int turn = state.getTurnCounter();

        // Turnos 0 & 1: Lógica simple (basada en V8)
        if (turn < 2) {
            switch (myMaki) {
                case 1: return 0.5;
                case 2: return 1.4;
                case 3: return 2.8;
                default: return 2.8 + (myMaki - 3) * 0.5;
            }
        }

        // Turno 2+ (Info más fiable): Lógica Relativa
        int nPlayers = state.getNPlayers();
        int maxOpponentMaki = 0;
        int secondMaxOpponentMaki = 0;
        int nFirstPlaceTies = 0;
        int nSecondPlaceTies = 0;
        boolean amFirst = true;

        for (int i = 0; i < nPlayers; i++) {
            if (i == playerId) continue;
            int oppMaki = state.getPlayedCardTypes(SGCardType.Maki, i).getValue();

            if (oppMaki > myMaki) amFirst = false;
            if (oppMaki == myMaki) nFirstPlaceTies++;

            if (oppMaki > maxOpponentMaki) {
                secondMaxOpponentMaki = maxOpponentMaki;
                maxOpponentMaki = oppMaki;
            } else if (oppMaki > secondMaxOpponentMaki) {
                secondMaxOpponentMaki = oppMaki;
            }
        }

        if (amFirst) {
            return 6.0 / (nFirstPlaceTies + 1); // +1 para mí
        }

        if (myMaki < maxOpponentMaki) {
            if (nPlayers > 2 && myMaki > secondMaxOpponentMaki) {
                return 3.0; // Solo en segundo lugar
            } else if (nPlayers > 2 && myMaki == secondMaxOpponentMaki) {
                for (int i = 0; i < nPlayers; i++) {
                    if (i != playerId && state.getPlayedCardTypes(SGCardType.Maki, i).getValue() == myMaki) {
                        nSecondPlaceTies++;
                    }
                }
                return 3.0 / (nSecondPlaceTies + 1); // +1 para mí
            }
        }
        return 0.0; // Ni primero ni segundo
    }

    /**
     * Calcula el valor *potencial* del Pudding.
     * Costo Medio: Un bucle de N_JUGADORES.
     * Conservado porque es una lógica de alto valor.
     */
    private double calculatePuddingScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypesAllGame()[playerId].get(SGCardType.Pudding).getValue();
        if (count == 0) return 0.0;

        int round = state.getRoundCounter();

        if (round < 2) { // Rondas 0 o 1
            return count * baseWeights.get(SGCardType.Pudding);
        } else { // Ronda Final (2)
            double myScore = state.getGameScore(playerId);
            double maxOppScore = 0.0;
            for (int i = 0; i < state.getNPlayers(); i++) {
                if (i == playerId) continue;
                maxOppScore = Math.max(maxOppScore, state.getGameScore(i));
            }
            if (myScore < maxOppScore) {
                return count * 6.0; // Valor alto si vas perdiendo
            } else {
                return count * 0.5; // Valor bajo si vas ganando
            }
        }
    }

    /**
     * Calcula el valor de los Palillos (Chopsticks).
     * Rápido: solo comprueba el turno.
     */
    private double calculateChopsticksScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Chopsticks, playerId).getValue();
        if (count == 0) return 0.0;

        int turn = state.getTurnCounter(); // 0-indexed

        if (turn >= 2 && turn <= 4) { // Turnos 3, 4, 5 (prime time)
            return count * (baseWeights.get(SGCardType.Chopsticks) * 1.5); // 50% bonus
        } else if (turn > 4) { // Turno 6+
            return count * 0.1; // Valor decae
        } else { // Turnos 1, 2
            return count * baseWeights.get(SGCardType.Chopsticks); // Valor normal
        }
    }

    /**
     * Calcula el pequeño bonus de "potencial" para los Dumplings.
     * Rápido: solo aritmética.
     */
    private double calculateDumplingScore(SGGameState state, int playerId) {
        int count = state.getPlayedCardTypes(SGCardType.Dumpling, playerId).getValue();
        // Añade el pequeño bonus de potencial si la colección no está llena
        if (count > 0 && count < 5) {
            return baseWeights.get(SGCardType.Dumpling); // Add 0.25
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
        return "SushiGoHeuristic_V_Fast_Denial"; // Nombre de clase actualizado
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SushiGoHeuristic_V4;
    }

    @Override
    public int hashCode() {
        return 82; // Nuevo hashcode para la versión con negación
    }
}
