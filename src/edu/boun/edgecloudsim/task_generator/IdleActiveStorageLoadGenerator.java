package edu.boun.edgecloudsim.task_generator;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.storage.ObjectGenerator;
import edu.boun.edgecloudsim.storage.RedisListHandler;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import edu.boun.edgecloudsim.utils.TaskProperty;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.*;

public class IdleActiveStorageLoadGenerator extends LoadGeneratorModel{
    int taskTypeOfDevices[];
    static private int numOfIOTasks=0;
    String orchestratorPolicy;
    String objectPlacementPolicy;
    Random random = new Random();
    RandomGenerator rand = new Well19937c(ObjectGenerator.seed);
    Map<Integer,Integer> activeCodedIOTasks;
    public IdleActiveStorageLoadGenerator(int _numberOfMobileDevices, double _simulationTime, String _simScenario, String _orchestratorPolicy,
                                          String _objectPlacementPolicy) {
        super(_numberOfMobileDevices, _simulationTime, _simScenario);
        orchestratorPolicy = _orchestratorPolicy;
        objectPlacementPolicy = _objectPlacementPolicy;
        random.setSeed(ObjectGenerator.getSeed());
        activeCodedIOTasks = new HashMap<>();

    }

    @Override
    public void initializeModel() {
        int ioTaskID=0;
        taskList = new ArrayList<TaskProperty>();
        ObjectGenerator OG = new ObjectGenerator(objectPlacementPolicy);

        //exponential number generator for file input size, file output size and task length
        ExponentialDistribution[][] expRngList = new ExponentialDistribution[SimSettings.getInstance().getTaskLookUpTable().length][ACTIVE_PERIOD];

        //create random number generator for each place
        for(int i=0; i<SimSettings.getInstance().getTaskLookUpTable().length; i++) {
            if(SimSettings.getInstance().getTaskLookUpTable()[i][USAGE_PERCENTAGE] ==0)
                continue;
            expRngList[i][LIST_DATA_UPLOAD] = new ExponentialDistribution(SimSettings.getInstance().getTaskLookUpTable()[i][DATA_UPLOAD]);
            expRngList[i][LIST_DATA_DOWNLOAD] = new ExponentialDistribution(SimSettings.getInstance().getTaskLookUpTable()[i][DATA_DOWNLOAD]);
            expRngList[i][LIST_TASK_LENGTH] = new ExponentialDistribution(SimSettings.getInstance().getTaskLookUpTable()[i][TASK_LENGTH]);
        }

        //Each mobile device utilizes an app type (task type)
        taskTypeOfDevices = new int[numberOfMobileDevices];
        for(int i=0; i<numberOfMobileDevices; i++) {
            int randomTaskType = -1;
//            double taskTypeSelector = SimUtils.getRandomDoubleNumber(0,100);
//            double taskTypePercentage = 0;

/*            for (int j=0; j<SimSettings.getInstance().getTaskLookUpTable().length; j++) {
                taskTypePercentage += SimSettings.getInstance().getTaskLookUpTable()[j][USAGE_PERCENTAGE];
                // Oleg: Select task if accumulated taskTypePercentage is more than random taskTypeSelector
                if(taskTypeSelector <= taskTypePercentage){
                    randomTaskType = j;
                    break;
                }
            }
            if(randomTaskType == -1){
                SimLogger.printLine("Impossible is occured! no random task type!");
                continue;
            }*/
            //TODO: currently select random task, not by weight
            randomTaskType = random.nextInt(SimSettings.getInstance().getTaskLookUpTable().length);
            taskTypeOfDevices[i] = randomTaskType;

            double poissonMean = SimSettings.getInstance().getTaskLookUpTable()[randomTaskType][POISSON_INTERARRIVAL];
            double activePeriod = SimSettings.getInstance().getTaskLookUpTable()[randomTaskType][ACTIVE_PERIOD];
            double idlePeriod = SimSettings.getInstance().getTaskLookUpTable()[randomTaskType][IDLE_PERIOD];
            double activePeriodStartTime = SimUtils.getRandomDoubleNumber(
                    SimSettings.CLIENT_ACTIVITY_START_TIME,
                    SimSettings.CLIENT_ACTIVITY_START_TIME + activePeriod);  //active period starts shortly after the simulation started (e.g. 10 seconds)
            double virtualTime = activePeriodStartTime;
            //storage
//            double samplingMethod = SimSettings.getInstance().getTaskLookUpTable()[randomTaskType][SAMPLING_METHOD];

//            ExponentialDistribution rng = new ExponentialDistribution(poissonMean);
            //Oleg: random with seed
            ExponentialDistribution rng = new ExponentialDistribution(rand,
                    poissonMean, ExponentialDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
            while(virtualTime < simulationTime) {
                double interval = rng.sample();
                if(interval <= 0){
                    SimLogger.printLine("Impossible is occured! interval is " + interval + " for device " + i + " time " + virtualTime);
                    continue;
                }
                //SimLogger.printLine(virtualTime + " -> " + interval + " for device " + i + " time ");
                virtualTime += interval;

                if(virtualTime > activePeriodStartTime + activePeriod){
                    activePeriodStartTime = activePeriodStartTime + activePeriod + idlePeriod;
                    virtualTime = activePeriodStartTime;
                    continue;
                }
                String objectID="";
//                String objectID = OG.getObjectID(SimSettings.getInstance().getNumOfDataObjects(),"objects");
                if(SimSettings.getInstance().isNsfExperiment()) {
                    //odd/even tasks will read only odd/even objects
                    while (1==1){
                        objectID = OG.getDataObjectID();
                        int objectNum = Integer.valueOf(objectID.replaceAll("[^\\d.]", ""));
                        if(objectNum%2 == randomTaskType)
                            break;
                    }
                }
                else
                    objectID = OG.getDataObjectID();

                taskList.add(new TaskProperty(i,randomTaskType, virtualTime, objectID, ioTaskID, 0,expRngList));
                ioTaskID++;
            }
        }
        numOfIOTasks = ioTaskID;
    }

    @Override
    public int getTaskTypeOfDevice(int deviceId) {
        // TODO Auto-generated method stub
        return taskTypeOfDevices[deviceId];
    }

    public boolean createParityTask(Task task){
        int taskType = task.getTaskType();
        int isParity=1;
        //If replication policy, read the same object, but mark it as parity
        //if DATA_PARITY_PLACE - if replica exists, read it
        if (SimManager.getInstance().getObjectPlacementPolicy().equalsIgnoreCase("REPLICATION_PLACE") ||
                SimManager.getInstance().getObjectPlacementPolicy().equalsIgnoreCase("DATA_PARITY_PLACE")) {
            String locations = RedisListHandler.getObjectLocations(task.getObjectRead());
            List<String> objectLocations = new ArrayList<String>(Arrays.asList(locations.split(" ")));
            //if no replicas and REPLICATION_PLACE
            if (objectLocations.size()==1 && SimManager.getInstance().getObjectPlacementPolicy().equalsIgnoreCase("REPLICATION_PLACE"))
                return false;
            else if(objectLocations.size()==0) {
                System.out.println("ERROR: No such object");
                System.exit(0);
            }
            else if(objectLocations.size()>= 2) {
                taskList.add(new TaskProperty(task.getMobileDeviceId(), taskType, CloudSim.clock(), task.getObjectRead(),
                        task.getIoTaskID(), isParity, 1, task.getCloudletFileSize(), task.getCloudletOutputSize(), task.getCloudletLength()));
                SimManager.getInstance().createNewTask();
                return true;
            }
        }
        List<String> mdObjects = RedisListHandler.getObjectsFromRedis("object:md*_"+task.getObjectRead()+"_*");
        //no parities
        if (mdObjects.size()==0)
            return false;
//        String stripeID = task.getStripeID();
        //TODO: currently selects random stripe
        String stripeID = RedisListHandler.getObjectID(mdObjects.get(random.nextInt(mdObjects.size())));
        String[] stripeObjects = RedisListHandler.getStripeObjects(stripeID);
        List<String> dataObjects = new ArrayList<String>(Arrays.asList(stripeObjects[0].split(" ")));
        List<String> parityObjects = new ArrayList<String>(Arrays.asList(stripeObjects[1].split(" ")));
        int i=0;
        int paritiesToRead=1;
/*        if (SimManager.getInstance().getOrchestratorPolicy().equalsIgnoreCase("IF_CONGESTED_READ_PARITY"))
            paritiesToRead=1;*/
        for (String objectID:dataObjects){
            i++;
            //if data object, skip
            if (objectID.equals(task.getObjectRead()))
                continue;
            //if not data, than data of parity
            //TODO: add delay for queue query
            taskList.add(new TaskProperty(task.getMobileDeviceId(),taskType, CloudSim.clock(),
                    objectID, task.getIoTaskID(), isParity,0,task.getCloudletFileSize(), task.getCloudletOutputSize(), task.getCloudletLength()));
            SimManager.getInstance().createNewTask();
        }
        for (String objectID:parityObjects){
            i++;
            taskList.add(new TaskProperty(task.getMobileDeviceId(),taskType, CloudSim.clock(),
                objectID, task.getIoTaskID(), isParity,paritiesToRead,task.getCloudletFileSize(), task.getCloudletOutputSize(), task.getCloudletLength()));
            SimManager.getInstance().createNewTask();
            //count just one read for queue
            paritiesToRead=0;
        }
        if (i!=(SimSettings.getInstance().getNumOfDataInStripe()+SimSettings.getInstance().getNumOfParityInStripe()))
            System.out.println("Not created tasks for all parities");
        activeCodedIOTasks.put(task.getIoTaskID(),i-1);
        return true;
    }

    public static int getNumOfIOTasks() {
        return numOfIOTasks;
    }

    //counts parities which haven't been read yet
    public int updateActiveCodedIOTasks(int ioTaskID, int operation){ //operation: 1 - reduce by 1, 0 - check, -1-remove key
        //if key doesn't exist
        if (!activeCodedIOTasks.containsKey(ioTaskID))
            return -1;
        if (operation==1) {
            activeCodedIOTasks.put(ioTaskID, activeCodedIOTasks.get(ioTaskID) - 1);
            return activeCodedIOTasks.get(ioTaskID);
        }
        if (operation==0){
            return activeCodedIOTasks.get(ioTaskID);
        }
        else if(operation==-1){
            activeCodedIOTasks.remove(ioTaskID);
            return -1;
        }
        return -2;
    }

}