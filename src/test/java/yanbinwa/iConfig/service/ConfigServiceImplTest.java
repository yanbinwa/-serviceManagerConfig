package yanbinwa.iConfig.service;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.ho.yaml.Yaml;

public class ConfigServiceImplTest
{

    @Test
    public void testYaml()
    {
        String testConfPath = "/Users/yanbinwa/Documents/workspace/springboot/serviceManager/serviceManagerConfig/conf/manifest.yml";
        File dumpFile = new File(testConfPath);
        try
        {
            @SuppressWarnings("rawtypes")
            Map properties = (Map) Yaml.loadType(dumpFile, HashMap.class);
            System.out.println(properties);
        } 
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
            fail("Can not read the yaml file");
        }
    }
    
//    @Test
//    public void checkConfigZnode() throws KeeperException, InterruptedException
//    {
//        ZooKeeper zk = ZkUtil.connectToZk("192.168.56.17:2181", new ZkWatcher());
//        JSONObject obj = ZkUtil.getData(zk, "/confManageNode/cache_standalone");
//        System.out.println("obj is: " + obj);
//    }
//
//    class ZkWatcher implements Watcher
//    {
//        @Override
//        public void process(WatchedEvent event)
//        {
//            System.out.println("Zookeeper event is: " + event);
//        } 
//    }
    
}
