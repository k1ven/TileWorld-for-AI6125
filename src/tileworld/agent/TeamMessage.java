package tileworld.agent;

/**
 * Structured team message used by cooperative agents.
 */
public class TeamMessage extends Message {

    public enum MessageType {
        POSITION,
        FUEL_STATION,
        CLAIM_TILE,
        CLAIM_HOLE,
        RELEASE_TILE,
        RELEASE_HOLE
    }

    private final MessageType type;
    private final int x;
    private final int y;
    private final long step;

    public TeamMessage(String from, String to, MessageType type, int x, int y, long step) {
        super(from, to, String.format("%s:%d:%d:%d", type.name(), x, y, step));
        this.type = type;
        this.x = x;
        this.y = y;
        this.step = step;
    }

    public MessageType getType() {
        return type;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public long getStep() {
        return step;
    }
}
