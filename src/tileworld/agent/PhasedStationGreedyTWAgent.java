package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * Baseline 4: current best version.
 * Fuel-station discovery is a phased background coverage task, while most
 * agents keep greedy scoring behavior.
 */
public class PhasedStationGreedyTWAgent extends GreedyTWAgent {

    public PhasedStationGreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel, AgentStrategy.PHASED_STATION);
    }
}
