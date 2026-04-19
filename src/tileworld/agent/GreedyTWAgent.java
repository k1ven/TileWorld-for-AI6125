package tileworld.agent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.TWDirection;
import tileworld.environment.TWEntity;
import tileworld.environment.TWEnvironment;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.AstarPathGenerator;
import tileworld.planners.TWPath;
import tileworld.planners.TWPathStep;

/**
 * Cooperative role-based agent with different behavior for sparse and dense settings.
 */
public class GreedyTWAgent extends TWAgent {
    private static final int STATION_BROADCAST_REPEATS = 3;
    private static final int STATION_SEARCH_STRIPES = 3;
    private static final Map<String, AgentDiagnostics> DIAGNOSTICS = new LinkedHashMap<String, AgentDiagnostics>();
    private static long firstStationStep = -1;
    private static String firstStationSource = "unknown";

    private enum SettingMode {
        SPARSE,
        DENSE
    }

    private enum Role {
        SCOUT,
        COLLECTOR,
        DELIVERER
    }

    protected enum AgentStrategy {
        BROADCAST_GREEDY,
        REGION_GREEDY,
        ROLE_SEARCH_CLAIM,
        PHASED_STATION
    }

    private final String name;
    private final int agentIndex;
    private final AgentStrategy strategy;
    private final SettingMode settingMode;
    private final Role role;
    private final AstarPathGenerator pathGenerator;

    private final double fuelSafetyMargin;
    private final double fuelSearchThreshold;
    private final int holePriorityBonus;
    private final int explorationStride;

    private Int2D explorationTarget;
    private Int2D knownFuelStation;
    private Int2D currentTileClaim;
    private Int2D currentHoleClaim;
    private Int2D stationSearchTarget;
    private int explorationPhase;
    private int stationSearchPhase;
    private int stationBroadcastsRemaining;

    private Map<String, Int2D> tileClaims;
    private Map<String, Int2D> holeClaims;

    public GreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        this(name, xpos, ypos, env, fuelLevel, AgentStrategy.PHASED_STATION);
    }

    protected GreedyTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel,
            AgentStrategy strategy) {
        super(xpos, ypos, env, fuelLevel);
        this.name = name;
        this.agentIndex = parseAgentIndex(name);
        this.strategy = strategy;
        this.settingMode = determineSettingMode(env);
        this.role = determineRole(agentIndex, strategy);
        this.fuelSafetyMargin = settingMode == SettingMode.DENSE ? 10.0 : 18.0;
        this.fuelSearchThreshold = settingMode == SettingMode.DENSE ? 0.12 : 0.20;
        this.holePriorityBonus = settingMode == SettingMode.DENSE ? 4 : 1;
        this.explorationStride = settingMode == SettingMode.DENSE ? 2 : 4;
        this.memory = createMemory(env);
        this.pathGenerator = new AstarPathGenerator(env, this, env.getxDimension() * env.getyDimension());
        this.tileClaims = new HashMap<String, Int2D>();
        this.holeClaims = new HashMap<String, Int2D>();
        this.explorationPhase = 0;
        this.stationSearchPhase = Math.max(0, agentIndex - 1) * 7;
        this.stationBroadcastsRemaining = 0;
        registerDiagnostics(name);
    }

    public static void resetDiagnostics() {
        DIAGNOSTICS.clear();
        firstStationStep = -1;
        firstStationSource = "unknown";
    }

    public static String diagnosticsSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Diag: stationStep=").append(firstStationStep)
                .append(", stationSource=").append(firstStationSource);
        for (Map.Entry<String, AgentDiagnostics> entry : DIAGNOSTICS.entrySet()) {
            AgentDiagnostics d = entry.getValue();
            builder.append(" | ").append(entry.getKey())
                    .append("(score=").append(d.score)
                    .append(",fuel=").append(String.format("%.0f", d.finalFuel))
                    .append(",known=").append(d.stationKnownStep)
                    .append(",pick=").append(d.pickups)
                    .append(",put=").append(d.putdowns)
                    .append(",refuel=").append(d.refuels)
                    .append(",search=").append(d.fuelSearchSteps)
                    .append(",pathNull=").append(d.pathNulls)
                    .append(",fallback=").append(d.fallbackMoves)
                    .append(",noFuel=").append(d.noFuelMoves)
                    .append(")");
        }
        return builder.toString();
    }

    private static void registerDiagnostics(String agentName) {
        if (!DIAGNOSTICS.containsKey(agentName)) {
            DIAGNOSTICS.put(agentName, new AgentDiagnostics());
        }
    }

    private AgentDiagnostics diagnostics() {
        AgentDiagnostics diagnostics = DIAGNOSTICS.get(name);
        if (diagnostics == null) {
            diagnostics = new AgentDiagnostics();
            DIAGNOSTICS.put(name, diagnostics);
        }
        return diagnostics;
    }

    private static class AgentDiagnostics {
        int pickups;
        int putdowns;
        int refuels;
        int fuelSearchSteps;
        int pathNulls;
        int fallbackMoves;
        int noFuelMoves;
        int score;
        double finalFuel;
        long stationKnownStep = -1;
    }

    @Override
    protected TWThought think() {
        readMessages();
        updateKnownFuelStation();
        reconcileCurrentCellMemory();

        if (shouldRefuelNow()) {
            if (getEnvironment().inFuelStation(this)) {
                clearClaims();
                return new TWThought(TWAction.REFUEL, TWDirection.Z);
            }
            clearClaims();
            return new TWThought(TWAction.MOVE, nextStepToward(knownFuelStation));
        }

        TWEntity here = (TWEntity) getEnvironment().getObjectGrid().get(getX(), getY());
        if (here instanceof TWHole && !carriedTiles.isEmpty()) {
            return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
        }
        if (here instanceof TWTile && carriedTiles.size() < 3 && shouldPickUpCurrentTile()) {
            return new TWThought(TWAction.PICKUP, TWDirection.Z);
        }

        Int2D target = chooseTarget();
        return new TWThought(TWAction.MOVE, nextStepToward(target));
    }

    @Override
    protected void act(TWThought thought) {
        try {
            switch (thought.getAction()) {
            case PICKUP:
                TWEntity tile = (TWEntity) getEnvironment().getObjectGrid().get(getX(), getY());
                if (tile instanceof TWTile) {
                    int carriedBefore = carriedTiles.size();
                    pickUpTile((TWTile) tile);
                    if (carriedTiles.size() > carriedBefore) {
                        diagnostics().pickups++;
                    }
                    getMemory().removeObject(tile);
                    currentTileClaim = null;
                }
                break;
            case PUTDOWN:
                TWEntity hole = (TWEntity) getEnvironment().getObjectGrid().get(getX(), getY());
                if (hole instanceof TWHole) {
                    int scoreBefore = score;
                    putTileInHole((TWHole) hole);
                    if (score > scoreBefore) {
                        diagnostics().putdowns++;
                    }
                    getMemory().removeObject(hole);
                    currentHoleClaim = null;
                }
                break;
            case REFUEL:
                double fuelBefore = fuelLevel;
                refuel();
                if (fuelLevel > fuelBefore) {
                    diagnostics().refuels++;
                }
                break;
            case MOVE:
            default:
                if (thought.getDirection() != TWDirection.Z && fuelLevel <= 0) {
                    diagnostics().noFuelMoves++;
                }
                move(thought.getDirection());
                break;
            }
            diagnostics().score = score;
            diagnostics().finalFuel = fuelLevel;
        } catch (CellBlockedException ex) {
            explorationTarget = null;
            diagnostics().finalFuel = fuelLevel;
        }
    }

    @Override
    public void communicate() {
        long step = getEnvironment().schedule.getSteps();

        if (knownFuelStation != null && stationBroadcastsRemaining > 0) {
            broadcast(new TeamMessage(getName(), "all", TeamMessage.MessageType.FUEL_STATION,
                    knownFuelStation.x, knownFuelStation.y, step));
            stationBroadcastsRemaining--;
        }
        if (!usesClaims()) {
            return;
        }
        if (currentTileClaim != null) {
            broadcast(new TeamMessage(getName(), "all", TeamMessage.MessageType.CLAIM_TILE,
                    currentTileClaim.x, currentTileClaim.y, step));
        }
        if (currentHoleClaim != null) {
            broadcast(new TeamMessage(getName(), "all", TeamMessage.MessageType.CLAIM_HOLE,
                    currentHoleClaim.x, currentHoleClaim.y, step));
        }
    }

    private GreedyTWAgentMemory createMemory(TWEnvironment env) {
        if (settingMode == SettingMode.SPARSE) {
            return new GreedyTWAgentMemory(this, env.schedule, env.getxDimension(), env.getyDimension(),
                    20.0, 20.0, 35.0);
        }
        return new GreedyTWAgentMemory(this, env.schedule, env.getxDimension(), env.getyDimension(),
                8.0, 8.0, 14.0);
    }

    private SettingMode determineSettingMode(TWEnvironment env) {
        if (Parameters.useDenseAgentSettings) {
            return SettingMode.DENSE;
        }
        return env.getxDimension() >= 80 || Parameters.tileMean >= 1.0 ? SettingMode.DENSE : SettingMode.SPARSE;
    }

    private Role determineRole(int index, AgentStrategy agentStrategy) {
        if (agentStrategy == AgentStrategy.BROADCAST_GREEDY || agentStrategy == AgentStrategy.REGION_GREEDY) {
            return Role.COLLECTOR;
        }
        if (index <= 2) {
            return Role.SCOUT;
        }
        if (index <= 4) {
            return Role.COLLECTOR;
        }
        return Role.DELIVERER;
    }

    private boolean shouldRefuelNow() {
        if (knownFuelStation == null) {
            return fuelLevel < Parameters.defaultFuelLevel * fuelSearchThreshold;
        }
        int distanceToFuel = manhattanDistance(knownFuelStation);
        double roleMargin = role == Role.DELIVERER ? fuelSafetyMargin + 4 : fuelSafetyMargin;
        return fuelLevel <= distanceToFuel + roleMargin;
    }

    private boolean shouldSearchFuelStation() {
        if (knownFuelStation != null) {
            return false;
        }
        if (strategy == AgentStrategy.BROADCAST_GREEDY || strategy == AgentStrategy.REGION_GREEDY) {
            return false;
        }
        if (strategy == AgentStrategy.ROLE_SEARCH_CLAIM) {
            return role == Role.SCOUT;
        }
        if (settingMode == SettingMode.DENSE) {
            return shouldBackgroundSearchFuelStation();
        }
        if (role == Role.SCOUT) {
            return true;
        }
        double threshold = settingMode == SettingMode.DENSE ? 0.70 : 0.45;
        return fuelLevel <= Parameters.defaultFuelLevel * threshold;
    }

    private Int2D chooseTarget() {
        if (settingMode == SettingMode.DENSE && strategy == AgentStrategy.PHASED_STATION) {
            return chooseDenseTarget();
        }

        Int2D tileTarget = findRememberedTarget(TWTile.class, tileClaims);
        Int2D holeTarget = findRememberedTarget(TWHole.class, holeClaims);

        if (shouldSearchFuelStation()) {
            clearClaims();
            diagnostics().fuelSearchSteps++;
            return chooseExplorationTarget();
        }

        if (carriedTiles.isEmpty()) {
            if (tileTarget != null) {
                currentTileClaim = tileTarget;
                currentHoleClaim = null;
                explorationTarget = null;
                return tileTarget;
            }
        } else {
            if (shouldDeliverNow(tileTarget, holeTarget)) {
                if (holeTarget != null) {
                    currentHoleClaim = holeTarget;
                    currentTileClaim = null;
                    explorationTarget = null;
                    return holeTarget;
                }
            } else if (tileTarget != null && carriedTiles.size() < 3) {
                currentTileClaim = tileTarget;
                currentHoleClaim = null;
                explorationTarget = null;
                return tileTarget;
            }

            if (holeTarget != null) {
                currentHoleClaim = holeTarget;
                currentTileClaim = null;
                explorationTarget = null;
                return holeTarget;
            }
        }

        clearClaims();
        if (explorationTarget == null || reached(explorationTarget)) {
            explorationTarget = chooseExplorationTarget();
        }
        return explorationTarget;
    }

    private Int2D chooseDenseTarget() {
        Int2D tileTarget = findRememberedTarget(TWTile.class, tileClaims);
        Int2D holeTarget = findRememberedTarget(TWHole.class, holeClaims);

        if (shouldBackgroundSearchFuelStation()) {
            Int2D opportunity = chooseStationSearchOpportunity();
            if (opportunity != null) {
                return opportunity;
            }
            clearClaims();
            diagnostics().fuelSearchSteps++;
            return chooseStationSearchTarget();
        }

        if (carriedTiles.isEmpty()) {
            if (tileTarget != null) {
                currentTileClaim = tileTarget;
                currentHoleClaim = null;
                explorationTarget = null;
                return tileTarget;
            }
        } else {
            if (shouldDeliverNow(tileTarget, holeTarget)) {
                if (holeTarget != null) {
                    currentHoleClaim = holeTarget;
                    currentTileClaim = null;
                    explorationTarget = null;
                    return holeTarget;
                }
            } else if (tileTarget != null && carriedTiles.size() < 3) {
                currentTileClaim = tileTarget;
                currentHoleClaim = null;
                explorationTarget = null;
                return tileTarget;
            }

            if (holeTarget != null) {
                currentHoleClaim = holeTarget;
                currentTileClaim = null;
                explorationTarget = null;
                return holeTarget;
            }
        }

        clearClaims();
        if (explorationTarget == null || reached(explorationTarget)) {
            explorationTarget = chooseExplorationTarget();
        }
        return explorationTarget;
    }

    private boolean shouldBackgroundSearchFuelStation() {
        if (strategy != AgentStrategy.PHASED_STATION) {
            return false;
        }
        if (knownFuelStation != null) {
            return false;
        }

        long step = getEnvironment().schedule.getSteps();
        if (agentIndex == 1) {
            return true;
        }
        if (agentIndex == 2) {
            return step >= stationSearchT1();
        }
        if (agentIndex == 3) {
            return step >= stationSearchT2() || fuelLevel <= Parameters.defaultFuelLevel * 0.45;
        }
        return false;
    }

    private int stationSearchT1() {
        return Math.max(50, Parameters.defaultFuelLevel / 5);
    }

    private int stationSearchT2() {
        return Math.max(stationSearchT1() + 50, (Parameters.defaultFuelLevel * 2) / 5);
    }

    private Int2D chooseStationSearchOpportunity() {
        if (!carriedTiles.isEmpty()) {
            Int2D holeTarget = targetFromMemory((TWEntity) getMemory().getClosestObjectInSensorRange(TWHole.class),
                    TWHole.class);
            holeTarget = applyClaimFilter(holeTarget, holeClaims);
            if (holeTarget != null && manhattanDistance(holeTarget) <= 2) {
                currentHoleClaim = holeTarget;
                currentTileClaim = null;
                explorationTarget = null;
                return holeTarget;
            }
        }

        if (carriedTiles.size() < 2) {
            Int2D tileTarget = targetFromMemory((TWEntity) getMemory().getClosestObjectInSensorRange(TWTile.class),
                    TWTile.class);
            tileTarget = applyClaimFilter(tileTarget, tileClaims);
            if (tileTarget != null && manhattanDistance(tileTarget) <= 1) {
                currentTileClaim = tileTarget;
                currentHoleClaim = null;
                explorationTarget = null;
                return tileTarget;
            }
        }

        return null;
    }

    private Int2D chooseStationSearchTarget() {
        if (stationSearchTarget != null && !stationSearchTargetCovered(stationSearchTarget)) {
            return stationSearchTarget;
        }

        int width = getEnvironment().getxDimension();
        int height = getEnvironment().getyDimension();
        int scoutSlot = Math.max(0, Math.min(agentIndex - 1, STATION_SEARCH_STRIPES - 1));

        int minX = (scoutSlot * width) / STATION_SEARCH_STRIPES;
        int maxX = ((scoutSlot + 1) * width) / STATION_SEARCH_STRIPES - 1;
        if (scoutSlot == STATION_SEARCH_STRIPES - 1) {
            maxX = width - 1;
        }

        int spacing = Math.max(1, Parameters.defaultSensorRange * 2);
        int gridColumns = Math.max(1, ((maxX - minX) / spacing) + 1);
        int gridRows = Math.max(1, ((height - 1) / spacing) + 1);
        int phase = stationSearchPhase++;
        int gridRow = (phase / gridColumns) % gridRows;
        int rawColumn = phase % gridColumns;
        int gridColumn = gridRow % 2 == 0 ? rawColumn : gridColumns - 1 - rawColumn;

        int x = Math.min(maxX, minX + gridColumn * spacing + Parameters.defaultSensorRange);
        int y = Math.min(height - 1, gridRow * spacing + Parameters.defaultSensorRange);
        stationSearchTarget = new Int2D(x, y);
        explorationTarget = null;
        return stationSearchTarget;
    }

    private boolean stationSearchTargetCovered(Int2D target) {
        return target != null && manhattanDistance(target) <= Math.max(1, Parameters.defaultSensorRange);
    }

    private boolean shouldDeliverNow(Int2D tileTarget, Int2D holeTarget) {
        if (carriedTiles.isEmpty() || holeTarget == null) {
            return false;
        }
        if (carriedTiles.size() >= 3) {
            return true;
        }
        if (role == Role.DELIVERER) {
            return true;
        }
        if (knownFuelStation != null && fuelLevel <= manhattanDistance(knownFuelStation) + fuelSafetyMargin + 8) {
            return true;
        }
        if (tileTarget == null) {
            return true;
        }

        int holeDistance = manhattanDistance(holeTarget);
        int tileDistance = manhattanDistance(tileTarget);
        int bonus = role == Role.COLLECTOR ? holePriorityBonus - 1 : holePriorityBonus;
        return holeDistance <= tileDistance + bonus;
    }

    private boolean shouldPickUpCurrentTile() {
        if (carriedTiles.isEmpty()) {
            return true;
        }
        if (carriedTiles.size() >= 3) {
            return false;
        }
        if (role == Role.DELIVERER) {
            Int2D holeTarget = findRememberedTarget(TWHole.class, holeClaims);
            return !shouldDeliverNow(new Int2D(getX(), getY()), holeTarget);
        }
        return true;
    }

    private Int2D findRememberedTarget(Class<? extends TWEntity> type, Map<String, Int2D> claims) {
        TWEntity visible = (TWEntity) getMemory().getClosestObjectInSensorRange(type);
        Int2D visibleTarget = targetFromMemory(visible, type);
        visibleTarget = applyClaimFilter(visibleTarget, claims);
        if (visibleTarget != null) {
            return visibleTarget;
        }

        if (type == TWTile.class) {
            TWTile tile = getMemory().getNearbyTile(getX(), getY(), visibleThreshold());
            return applyClaimFilter(targetFromMemory(tile, type), claims);
        }
        TWHole hole = getMemory().getNearbyHole(getX(), getY(), visibleThreshold());
        return applyClaimFilter(targetFromMemory(hole, type), claims);
    }

    private double visibleThreshold() {
        return settingMode == SettingMode.DENSE ? 6.0 : 18.0;
    }

    private Int2D targetFromMemory(TWEntity candidate, Class<? extends TWEntity> expectedType) {
        if (candidate == null) {
            return null;
        }
        return expectedType.isInstance(candidate) ? new Int2D(candidate.getX(), candidate.getY()) : null;
    }

    private Int2D applyClaimFilter(Int2D target, Map<String, Int2D> claims) {
        if (target == null) {
            return null;
        }
        if (!usesClaims()) {
            return target;
        }
        for (Map.Entry<String, Int2D> entry : claims.entrySet()) {
            if (name.equals(entry.getKey())) {
                continue;
            }
            Int2D claim = entry.getValue();
            if (claim != null && claim.x == target.x && claim.y == target.y) {
                return null;
            }
        }
        return target;
    }

    private TWDirection nextStepToward(Int2D target) {
        if (target == null || reached(target)) {
            diagnostics().fallbackMoves++;
            return chooseFallbackMove();
        }

        TWPath path = pathGenerator.findPath(getX(), getY(), target.x, target.y);
        if (path != null && path.hasNext()) {
            TWPathStep step = path.popNext();
            if (step.getDirection() == TWDirection.Z && path.hasNext()) {
                step = path.popNext();
            }
            return step.getDirection();
        }

        diagnostics().pathNulls++;
        return safeGreedyDirection(target.x, target.y);
    }

    private TWDirection chooseFallbackMove() {
        TWDirection[] order = role == Role.SCOUT
                ? new TWDirection[] { TWDirection.E, TWDirection.S, TWDirection.W, TWDirection.N }
                : new TWDirection[] { TWDirection.N, TWDirection.S, TWDirection.E, TWDirection.W };
        for (TWDirection direction : order) {
            if (canMove(direction)) {
                return direction;
            }
        }
        return TWDirection.Z;
    }

    private TWDirection safeGreedyDirection(int targetX, int targetY) {
        TWDirection preferred;
        if (Math.abs(targetX - getX()) >= Math.abs(targetY - getY())) {
            preferred = targetX > getX() ? TWDirection.E : TWDirection.W;
            if (targetX == getX()) {
                preferred = targetY > getY() ? TWDirection.S : TWDirection.N;
            }
        } else {
            preferred = targetY > getY() ? TWDirection.S : TWDirection.N;
        }

        if (canMove(preferred)) {
            return preferred;
        }

        TWDirection secondary = preferred == TWDirection.E || preferred == TWDirection.W
                ? (targetY > getY() ? TWDirection.S : TWDirection.N)
                : (targetX > getX() ? TWDirection.E : TWDirection.W);
        if (canMove(secondary)) {
            return secondary;
        }
        return chooseFallbackMove();
    }

    private boolean canMove(TWDirection direction) {
        int nextX = getX() + direction.dx;
        int nextY = getY() + direction.dy;
        return direction == TWDirection.Z || !getEnvironment().isCellBlocked(nextX, nextY);
    }

    private Int2D chooseExplorationTarget() {
        int width = getEnvironment().getxDimension();
        int height = getEnvironment().getyDimension();
        if (strategy == AgentStrategy.BROADCAST_GREEDY) {
            return chooseGlobalExplorationTarget(width, height);
        }
        int columns = 3;
        int rows = 2;
        int regionWidth = Math.max(1, width / columns);
        int regionHeight = Math.max(1, height / rows);
        int region = Math.max(0, Math.min(agentIndex - 1, columns * rows - 1));
        int column = region % columns;
        int row = region / columns;

        int minX = column * regionWidth;
        int maxX = column == columns - 1 ? width - 1 : (column + 1) * regionWidth - 1;
        int minY = row * regionHeight;
        int maxY = row == rows - 1 ? height - 1 : (row + 1) * regionHeight - 1;

        if (settingMode == SettingMode.SPARSE) {
            Int2D[] patrolPoints = new Int2D[] {
                    new Int2D(minX, minY),
                    new Int2D(maxX, minY),
                    new Int2D(maxX, maxY),
                    new Int2D(minX, maxY),
                    new Int2D((minX + maxX) / 2, (minY + maxY) / 2)
            };
            Int2D target = patrolPoints[explorationPhase % patrolPoints.length];
            explorationPhase += Math.max(1, explorationStride / 2);
            return target;
        }

        int spacing = Math.max(1, Parameters.defaultSensorRange * 2);
        int gridColumns = Math.max(1, ((maxX - minX) / spacing) + 1);
        int gridRows = Math.max(1, ((maxY - minY) / spacing) + 1);
        int phase = explorationPhase++;
        int gridRow = (phase / gridColumns) % gridRows;
        int rawColumn = phase % gridColumns;
        int gridColumn = gridRow % 2 == 0 ? rawColumn : gridColumns - 1 - rawColumn;

        int x = Math.min(maxX, minX + gridColumn * spacing + Parameters.defaultSensorRange);
        int y = Math.min(maxY, minY + gridRow * spacing + Parameters.defaultSensorRange);
        return new Int2D(x, y);
    }

    private Int2D chooseGlobalExplorationTarget(int width, int height) {
        int spacing = Math.max(1, Parameters.defaultSensorRange * 2);
        int gridColumns = Math.max(1, ((width - 1) / spacing) + 1);
        int gridRows = Math.max(1, ((height - 1) / spacing) + 1);
        int phase = explorationPhase++ + Math.max(0, agentIndex - 1) * 5;
        int gridRow = (phase / gridColumns) % gridRows;
        int rawColumn = phase % gridColumns;
        int gridColumn = gridRow % 2 == 0 ? rawColumn : gridColumns - 1 - rawColumn;

        int x = Math.min(width - 1, gridColumn * spacing + Parameters.defaultSensorRange);
        int y = Math.min(height - 1, gridRow * spacing + Parameters.defaultSensorRange);
        return new Int2D(x, y);
    }

    private void updateKnownFuelStation() {
        int minX = Math.max(0, getX() - Parameters.defaultSensorRange);
        int maxX = Math.min(getEnvironment().getxDimension() - 1, getX() + Parameters.defaultSensorRange);
        int minY = Math.max(0, getY() - Parameters.defaultSensorRange);
        int maxY = Math.min(getEnvironment().getyDimension() - 1, getY() + Parameters.defaultSensorRange);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                TWEntity entity = (TWEntity) getEnvironment().getObjectGrid().get(x, y);
                if (entity instanceof TWFuelStation) {
                    rememberFuelStation(new Int2D(x, y), "vision", true);
                    return;
                }
            }
        }
    }

    private void readMessages() {
        tileClaims.clear();
        holeClaims.clear();

        for (Message message : getEnvironment().getMessages()) {
            if (!(message instanceof TeamMessage)) {
                continue;
            }

            TeamMessage teamMessage = (TeamMessage) message;
            Int2D location = new Int2D(teamMessage.getX(), teamMessage.getY());

            switch (teamMessage.getType()) {
            case FUEL_STATION:
                rememberFuelStation(location, "message", false);
                break;
            case CLAIM_TILE:
                if (usesClaims()) {
                    tileClaims.put(teamMessage.getFrom(), location);
                }
                break;
            case CLAIM_HOLE:
                if (usesClaims()) {
                    holeClaims.put(teamMessage.getFrom(), location);
                }
                break;
            case RELEASE_TILE:
                if (usesClaims()) {
                    tileClaims.remove(teamMessage.getFrom());
                }
                break;
            case RELEASE_HOLE:
                if (usesClaims()) {
                    holeClaims.remove(teamMessage.getFrom());
                }
                break;
            default:
                break;
            }
        }
    }

    private void broadcast(TeamMessage message) {
        getEnvironment().receiveMessage(message);
    }

    private void rememberFuelStation(Int2D location, String source, boolean broadcastIfNew) {
        boolean newlyKnown = knownFuelStation == null
                || knownFuelStation.x != location.x
                || knownFuelStation.y != location.y;
        knownFuelStation = location;
        recordStationKnown(source);
        if (newlyKnown && broadcastIfNew) {
            stationBroadcastsRemaining = STATION_BROADCAST_REPEATS;
        }
    }

    private void recordStationKnown(String source) {
        long step = getEnvironment().schedule.getSteps();
        if (diagnostics().stationKnownStep < 0) {
            diagnostics().stationKnownStep = step;
        }
        if (firstStationStep < 0) {
            firstStationStep = step;
            firstStationSource = getName() + ":" + source;
        }
    }

    private void reconcileCurrentCellMemory() {
        TWEntity here = (TWEntity) getEnvironment().getObjectGrid().get(getX(), getY());
        if (!(here instanceof TWTile) && !(here instanceof TWHole)) {
            getMemory().removeAgentPercept(getX(), getY());
            if (currentTileClaim != null && reached(currentTileClaim)) {
                currentTileClaim = null;
            }
            if (currentHoleClaim != null && reached(currentHoleClaim)) {
                currentHoleClaim = null;
            }
        }
    }

    private void clearClaims() {
        currentTileClaim = null;
        currentHoleClaim = null;
    }

    private boolean usesClaims() {
        return strategy == AgentStrategy.ROLE_SEARCH_CLAIM || strategy == AgentStrategy.PHASED_STATION;
    }

    private int parseAgentIndex(String agentName) {
        String digits = agentName.replaceAll("\\D+", "");
        if (digits.length() == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private boolean reached(Int2D target) {
        return target != null && target.x == getX() && target.y == getY();
    }

    private int manhattanDistance(Int2D target) {
        return Math.abs(target.x - getX()) + Math.abs(target.y - getY());
    }

    @Override
    public String getName() {
        return name;
    }
}
