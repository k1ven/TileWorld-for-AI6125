package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * Baseline 1: pure greedy agents.
 * Agents only share known fuel-station information; there are no claims,
 * fixed roles, or station-search assignments.
 */
public class BroadcastGreedyTWAgent extends GreedyTWAgent {

    public BroadcastGreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel, AgentStrategy.BROADCAST_GREEDY);
    }
}
