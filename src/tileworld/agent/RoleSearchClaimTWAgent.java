package tileworld.agent;

import tileworld.environment.TWEnvironment;

/**
 * Baseline 3: static roles plus simple claims.
 * agent1-agent2 are scouts that search for the fuel station, agent3-agent4 are
 * collectors, and agent5-agent6 are deliverers. Tile/hole claims are shared to
 * reduce conflicts.
 */
public class RoleSearchClaimTWAgent extends GreedyTWAgent {

    public RoleSearchClaimTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel, AgentStrategy.ROLE_SEARCH_CLAIM);
    }
}
