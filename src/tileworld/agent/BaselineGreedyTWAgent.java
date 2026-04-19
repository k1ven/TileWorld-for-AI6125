package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * Baseline: greedy behavior without communication, region partitioning, roles, or claims.
 */
public class BaselineGreedyTWAgent extends GreedyTWAgent {

    public BaselineGreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel, AgentStrategy.BROADCAST_GREEDY);
    }

    @Override
    public void communicate() {
        // Baseline intentionally disables communication.
    }
}
