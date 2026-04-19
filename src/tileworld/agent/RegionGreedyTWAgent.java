package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * Baseline 2: region-biased greedy agents.
 * Agents use the same greedy behavior but their fallback exploration is biased
 * to six different map regions. They still only share fuel-station information.
 */
public class RegionGreedyTWAgent extends GreedyTWAgent {

    public RegionGreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel, AgentStrategy.REGION_GREEDY);
    }
}
