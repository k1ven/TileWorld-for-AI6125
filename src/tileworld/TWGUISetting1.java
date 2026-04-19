package tileworld;

public class TWGUISetting1 {
    public static void main(String[] args) {
        Parameters.useSetting1();
        if (args != null && args.length > 0) {
            long seed = Long.parseLong(args[0]);
            TWGUI twGui = new TWGUI(seed);
            sim.display.Console c = new sim.display.Console(twGui);
            c.setVisible(true);
            return;
        }
        TWGUI.main(args);
    }
}
