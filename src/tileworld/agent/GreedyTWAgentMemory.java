package tileworld.agent;

import sim.engine.Schedule;
import sim.field.grid.ObjectGrid2D;
import sim.util.Bag;
import sim.util.IntBag;
import tileworld.environment.TWEntity;
import tileworld.environment.TWHole;
import tileworld.environment.TWObstacle;
import tileworld.environment.TWTile;

/**
 * Custom memory for GreedyTWAgent.
 * Keeps the default working-memory behavior but forgets stale objects after
 * type-specific time windows.
 */
public class GreedyTWAgentMemory extends TWAgentWorkingMemory {
    private final Schedule schedule;
    private final double[][] lastSeenTimes;
    private final int width;
    private final int height;
    private final double tileMemorySpan;
    private final double holeMemorySpan;
    private final double obstacleMemorySpan;

    public GreedyTWAgentMemory(TWAgent agent, Schedule schedule, int width, int height,
            double tileMemorySpan, double holeMemorySpan, double obstacleMemorySpan) {
        super(agent, schedule, width, height);
        this.schedule = schedule;
        this.width = width;
        this.height = height;
        this.tileMemorySpan = tileMemorySpan;
        this.holeMemorySpan = holeMemorySpan;
        this.obstacleMemorySpan = obstacleMemorySpan;
        this.lastSeenTimes = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                lastSeenTimes[x][y] = Double.NEGATIVE_INFINITY;
            }
        }
    }

    @Override
    public void updateMemory(Bag sensedObjects, IntBag objectXCoords, IntBag objectYCoords,
            Bag sensedAgents, IntBag agentXCoords, IntBag agentYCoords) {
        super.updateMemory(sensedObjects, objectXCoords, objectYCoords, sensedAgents, agentXCoords, agentYCoords);

        double now = schedule.getTime();
        for (int i = 0; i < sensedObjects.size(); i++) {
            Object object = sensedObjects.get(i);
            if (!(object instanceof TWEntity)) {
                continue;
            }

            TWEntity entity = (TWEntity) object;
            lastSeenTimes[entity.getX()][entity.getY()] = now;
        }

        flushStaleMemory(now);
    }

    @Override
    public void removeAgentPercept(int x, int y) {
        super.removeAgentPercept(x, y);
        getMemoryGrid().set(x, y, null);
        lastSeenTimes[x][y] = Double.NEGATIVE_INFINITY;
    }

    @Override
    public void removeObject(TWEntity object) {
        if (object == null) {
            return;
        }
        removeAgentPercept(object.getX(), object.getY());
    }

    public double getAge(int x, int y) {
        return schedule.getTime() - lastSeenTimes[x][y];
    }

    private void flushStaleMemory(double now) {
        ObjectGrid2D memoryGrid = getMemoryGrid();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                TWEntity entity = (TWEntity) memoryGrid.get(x, y);
                if (entity == null) {
                    continue;
                }

                double ttl = getMemorySpan(entity);
                if (ttl < 0) {
                    continue;
                }

                if (now - lastSeenTimes[x][y] > ttl) {
                    removeAgentPercept(x, y);
                }
            }
        }
    }

    private double getMemorySpan(TWEntity entity) {
        if (entity instanceof TWTile) {
            return tileMemorySpan;
        }
        if (entity instanceof TWHole) {
            return holeMemorySpan;
        }
        if (entity instanceof TWObstacle) {
            return obstacleMemorySpan;
        }
        return -1.0;
    }
}
