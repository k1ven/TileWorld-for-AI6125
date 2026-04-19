package tileworld;

/**
 * Parameters
 *
 * @author michaellees
 * Created: Apr 21, 2010
 *
 * Copyright michaellees 
 *
 * Description:
 *
 * Class used to store global simulation parameters.
 * Environment related parameters are still in the TWEnvironment class.
 *
 */
public class Parameters {

    //Simulation Parameters
    public static int seed = 4162012; //no effect with gui
    public static long endTime = 5000; //no effect with gui
    public static int benchmarkIterations = 10;

    //Agent Parameters
    public static int defaultFuelLevel = 500;
    public static int defaultSensorRange = 3;
    public static boolean printActionLog = false;
    public static boolean printDiagnostics = false;
    public static boolean useDenseAgentSettings = true;

    //Environment Parameters
    public static int xDimension = 120; //size in cells
    public static int yDimension = 120;

    //Object Parameters
    // mean, dev: control the number of objects to be created in every time step (i.e. average object creation rate)
    public static double tileMean = 5;
    public static double holeMean = 5;
    public static double obstacleMean = 5;
    public static double tileDev = 0.5f;
    public static double holeDev = 0.5f;
    public static double obstacleDev = 0.5f;
    // the life time of each object
    public static int lifeTime = 50;

    public static void useSetting1() {
        endTime = 5000;
        benchmarkIterations = 10;
        defaultFuelLevel = 500;
        defaultSensorRange = 3;
        printActionLog = false;
        printDiagnostics = false;
        useDenseAgentSettings = true;
        xDimension = 50;
        yDimension = 50;
        tileMean = 0.2;
        holeMean = 0.2;
        obstacleMean = 0.2;
        tileDev = 0.5f;
        holeDev = 0.5f;
        obstacleDev = 0.5f;
        lifeTime = 100;
    }

    public static void useSetting2() {
        seed = Parameters2.seed;
        endTime = Parameters2.endTime;
        benchmarkIterations = 10;
        defaultFuelLevel = Parameters2.defaultFuelLevel;
        defaultSensorRange = Parameters2.defaultSensorRange;
        printActionLog = false;
        printDiagnostics = false;
        useDenseAgentSettings = true;
        xDimension = Parameters2.xDimension;
        yDimension = Parameters2.yDimension;
        tileMean = Parameters2.tileMean;
        holeMean = Parameters2.holeMean;
        obstacleMean = Parameters2.obstacleMean;
        tileDev = Parameters2.tileDev;
        holeDev = Parameters2.holeDev;
        obstacleDev = Parameters2.obstacleDev;
        lifeTime = Parameters2.lifeTime;
    }

}
