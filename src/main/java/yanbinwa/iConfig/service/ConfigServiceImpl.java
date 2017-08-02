package yanbinwa.iConfig.service;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import yanbinwa.common.constants.CommonConstants;
import yanbinwa.common.exceptions.ServiceUnavailableException;
import yanbinwa.common.utils.YamlUtil;
import yanbinwa.common.utils.ZkUtil;
import yanbinwa.common.zNodedata.ZNodeDataUtil;
import yanbinwa.common.zNodedata.ZNodeServiceData;
import yanbinwa.common.zNodedata.ZNodeServiceDataImpl;

/**
 * 
 * 这里就是设计成单点设备，不考虑HA
 * 
 * 在读取manifest.yaml文件时，组织一个map，key为ZnodeData，value为每一个服务对应的Map
 * orchestration中有dependence等内容），之后将该Map写入到Znode中，以供相应的服务读取配置，
 * 应该有一个线程来monitor文件修改，一旦文件修改了，就重新load，进行对比，找到差异（可能会引起
 * Deploy service）
 * 
 * 对应的服务有三种状态，一种是等待配置，一种是等待上线，一种是正在提供服务
 * 
 * @author yanbinwa
 *
 */

@Service("configService")
@EnableAutoConfiguration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "serviceProperties")
public class ConfigServiceImpl implements ConfigService
{
    private static final Logger logger = Logger.getLogger(ConfigServiceImpl.class);
    
    private String manifestFile;
    private Map<String, String> serviceDataProperties;
    private Map<String, String> zNodeInfoProperties;
    
    ZNodeServiceData serviceData = null;
    String confZnodePath = null;
    String deployServiceName = null;
    
    /** ServiceName to Service Properties */
    @SuppressWarnings("rawtypes")
    Map<String, Map> serviceNameToServicePropertiesMap = new HashMap<String, Map>();
    
    Map<String, Object> zNodeDepolyInfoMap = null;
    
    String zookeeperHostIp = null;
    volatile boolean isRunning = false;
    ZooKeeper zk = null;
    
    /** copyOnWrite lock */
    ReentrantLock lock = new ReentrantLock();
    
    Watcher zkWatcher = new ZkWatcher();
    
    public void setManifestFile(String manifestFile)
    {
        this.manifestFile = manifestFile;
    }
    
    public String getManifestFile()
    {
        return this.manifestFile;
    }
    
    public void setServiceDataProperties(Map<String, String> properties)
    {
        this.serviceDataProperties = properties;
    }
    
    public Map<String, String> getServiceDataProperties()
    {
        return this.serviceDataProperties;
    }
    
    public void setZNodeInfoProperties(Map<String, String> properties)
    {
        this.zNodeInfoProperties = properties;
    }
    
    public Map<String, String> getZNodeInfoProperties()
    {
        return this.zNodeInfoProperties;
    }
    
    @Override
    public void afterPropertiesSet() throws Exception
    {
        zookeeperHostIp = zNodeInfoProperties.get(CommonConstants.ZOOKEEPER_HOSTPORT_KEY);
        if(zookeeperHostIp == null)
        {
            logger.error("Zookeeper host and port should not be null");
            return;
        }
        
        confZnodePath = zNodeInfoProperties.get(CommonConstants.CONFZNODEPATH_KEY);
        if (confZnodePath == null)
        {
            logger.error("confZnodePath should not be null");
            return;
        }
        
        String serviceGroupName = serviceDataProperties.get(CommonConstants.SERVICE_SERVICEGROUPNAME);
        String serviceName = serviceDataProperties.get(CommonConstants.SERVICE_SERVICENAME);
        String ip = serviceDataProperties.get(CommonConstants.SERVICE_IP);
        String portStr = serviceDataProperties.get(CommonConstants.SERVICE_PORT);
        int port = Integer.parseInt(portStr);
        String rootUrl = serviceDataProperties.get(CommonConstants.SERVICE_ROOTURL);
        serviceData = new ZNodeServiceDataImpl(ip, serviceGroupName, serviceName, port, rootUrl);

        start();
    }

    
    /**
     * 先创建Zookeeper的连接，建立conf Znode，在此基础上建立conf的子Node，一旦有conf需要update，向Depoly server的子node写入
     * Depoly信息，并更新Update server的Znode
     * 
     */
    @Override
    public void start()
    {
        if (!isRunning)
        {
            isRunning = true;
            boolean ret = buildZookeeprConnection();
            if (!ret)
            {
                logger.error("ConfigServiceImpl can not connect to Zookeeper");
                return;
            }
            try
            {
                setUpZnodeForConfig();
                Map<String, Object> serviceInfoAndDeployInfoMap = loadConfigFile(manifestFile);
                updateServiceInfoAndDeployInfoMap(serviceInfoAndDeployInfoMap);
            } 
            catch (KeeperException e)
            {
                logger.error(e.getMessage());
            } 
            catch (InterruptedException e)
            {
                if (!isRunning)
                {
                    logger.info("service is stopped");
                }
                else
                {
                    e.printStackTrace();
                }
            }
        }
        else
        {
            logger.info("ConfigService serivce has readly started ...");
        }
    }

    
    /**
     * 删除config节点和子节点，断开zookeeper连接
     * 
     */
    @Override
    public void stop()
    {
        if (isRunning)
        {
            
        }
        else
        {
            logger.info("ConfigService serivce has readly stopped ...");
        }
    }
    
    @Override
    public String getServiceName() throws ServiceUnavailableException
    {
        if(!isServiceReadyToWork())
        {
            throw new ServiceUnavailableException();
        }
        return serviceData.getServiceName();
    }


    @Override
    public void startManageService()
    {
        if(!isServiceReadyToWork())
        {
            start();
        }
    }

    @Override
    public void stopManageService()
    {
        if(isServiceReadyToWork())
        {
            stop();
        }
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<String, Object> loadConfigFile(String fileName)
    {
        if (fileName == null)
        {
            logger.error("manifestFile should not be null");
            return null;
        }
        Map manifestMap = null;
        try
        {
            manifestMap = YamlUtil.getMapFromFile(fileName);
        } 
        catch (FileNotFoundException e)
        {
            logger.error("Fail to load the manifest file. message is " + e.getMessage());
            return null;
        }
        if (manifestMap == null)
        {
            logger.error("Fail to get the manifest from file: " + fileName);
            return null;
        }
        Map<String, Object> serviceInfoAndDeployInfoMap = new HashMap<String, Object>();
        
        //Get Service Info Map
        Object serviceInfoObject = manifestMap.get(ConfigService.SERVICE_INFO_KEY);
        if (serviceInfoObject == null || !(serviceInfoObject instanceof Map))
        {
            logger.error("Can not get the serviceInfoObject or serviceInfoObject is not a map");
            return null;
        }
        Map<String, Object> serviceInfoMap = (Map<String, Object>) serviceInfoObject;
        Object componenetsObj = serviceInfoMap.get(ConfigService.COMPONENTS_KEY);
        if (componenetsObj == null || !(componenetsObj instanceof Map))
        {
            logger.error("Can not get the componenetsObj or componenetsObj is not a map");
            return null;
        }
        Map<String, Object> componenetsMap = (Map<String, Object>) componenetsObj;
        Map<String, Map> serviceNameToServicePropertiesTmp = getServiceDataInfo(componenetsMap);
        serviceInfoAndDeployInfoMap.put(SERVICE_INFO_KEY, serviceNameToServicePropertiesTmp);
        
        //Get Deploy Info Map
        Object deployInfoObject = manifestMap.get(ConfigService.DEPLOY_INFO_KEY);
        if (deployInfoObject == null || !(deployInfoObject instanceof Map))
        {
            logger.error("Can not get the deployInfoObject or deployInfoObject is not a map");
            return null;
        }
        deployServiceName = (String)((Map<String, Object>)deployInfoObject).remove(DEPLOY_SERVICE_KEY);
        if (deployServiceName == null)
        {
            logger.error("deployServiceName is null");
            return null;
        }
        serviceInfoAndDeployInfoMap.put(DEPLOY_INFO_KEY, deployInfoObject);
        return serviceInfoAndDeployInfoMap;
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Map<String, Map> getServiceDataInfo(Map<String, Object> componenetsMap)
    {
        if (componenetsMap == null)
        {
            return null;
        }
        Map<String, Map> serviceNameToServicePropertiesMapTmp = new HashMap<String, Map>();
        //先是group，之后是device
        for (Map.Entry<String, Object> entry : componenetsMap.entrySet())
        {
            String serviceGroupName = entry.getKey();
            Object serviceGroupMapObj = entry.getValue();
            logger.trace("Start parse the service info for group " + serviceGroupName);
            if (serviceGroupMapObj == null || !(serviceGroupMapObj instanceof Map))
            {
                logger.error("serviceGroupMapObj shoud not be null or should be Map. Service group name is: " + serviceGroupName);
                continue;
            }
            Map<String, Object> serviceGroupMap = (Map<String, Object>)serviceGroupMapObj;
            Object deviceObj = serviceGroupMap.get(CommonConstants.DEVICES_KEY);
            if (deviceObj == null || !(deviceObj instanceof Map))
            {
                logger.error("deviceObj shoud not be null or should be Map. Service group name is: " + serviceGroupName);
                continue;
            }
            Map<String, Object> deviceMap = (Map<String, Object>)deviceObj;
            for (Map.Entry<String, Object> entry1 : deviceMap.entrySet())
            {
                String serviceName = entry1.getKey();
                Object serviceMapObj = entry1.getValue();
                if (serviceMapObj == null || !(serviceMapObj instanceof Map))
                {
                    logger.error("serviceMapObj shoud not be null or should be Map. Service name is: " + serviceName);
                    continue;
                }
                Map<String, Object> serviceMap = (Map<String, Object>)serviceMapObj;
                Object serviceDataPropertiesObj = serviceMap.get(CommonConstants.SERVICE_DATA_PROPERTIES_KEY);
                if (serviceDataPropertiesObj == null || !(serviceDataPropertiesObj instanceof Map))
                {
                    logger.error("serviceDataPropertiesObj shoud not be null or should be Map. Service name is: " + serviceName);
                    continue;
                }
                Map<String, Object> serviceDataProperties = (Map<String, Object>)serviceDataPropertiesObj;
                ZNodeServiceData zNodeServiceData = ZNodeDataUtil.getZnodeData(serviceDataProperties);
                if (zNodeServiceData == null)
                {
                    logger.error("zNodeServiceData should not be null. Service name is: " + serviceName);
                    continue;
                }
                if(!serviceGroupName.equals(zNodeServiceData.getServiceGroupName()) || 
                                                !serviceName.equals(zNodeServiceData.getServiceName()))
                {
                    logger.error("zNodeServiceData is not belongs to the serviceGroup " + serviceGroupName + 
                            " or servcie " + serviceName + "; The data is " + zNodeServiceData);
                    continue;
                }
                serviceNameToServicePropertiesMapTmp.put(serviceName, serviceMap);
                logger.info("Read the service propertie from group " + serviceGroupName + " and service " + serviceName);
            }
        }
        
        return serviceNameToServicePropertiesMapTmp;
    }
    
    private boolean buildZookeeprConnection()
    {
        if(zk != null)
        {
            try
            {
                ZkUtil.closeZk(zk);
                zk = null;
            } 
            catch (InterruptedException e)
            {
                logger.error("Fail to close the zookeeper connection at begin");
            }
        }
        zk = ZkUtil.connectToZk(zookeeperHostIp, zkWatcher);
        if (zk == null)
        {
            logger.error("Can not connect to zookeeper: " + zookeeperHostIp);
            return false;
        }
        if(zk.getState() == ZooKeeper.States.CONNECTING)
        {
            waitingForZookeeper();
        }
        return true;
    }
    
    private void setUpZnodeForConfig() throws KeeperException, InterruptedException
    {
        logger.info("setUpZnodeForConfig ...");
        
        if (ZkUtil.checkZnodeExist(zk, confZnodePath))
        {
            ZkUtil.setData(zk, confZnodePath, serviceData.createJsonObject());
        }
        else
        {
            String regZNodePathStr = ZkUtil.createPersistentZNode(zk, confZnodePath, serviceData.createJsonObject());
            logger.info("Create znode: " + regZNodePathStr);
        }
    }
    
    private void waitingForZookeeper()
    {
        logger.info("Waiting for the zookeeper...");
        while(zk.getState() == ZooKeeper.States.CONNECTING && isRunning)
        {
            try
            {
                Thread.sleep(ZK_WAIT_INTERVAL);
                logger.debug("Try to connection to zookeeper");
            } 
            catch (InterruptedException e)
            {
                if (!isRunning)
                {
                    logger.info("Stop this thread");
                }
                else
                {
                    e.printStackTrace();
                }
            }            
        }
        logger.info("Connected to the zookeeper " + zookeeperHostIp);
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void updateServiceInfoAndDeployInfoMap(Map<String, Object> serviceInfoAndDeployInfoMap) throws KeeperException, InterruptedException
    {
        lock.lock();
        try
        {
            if (serviceInfoAndDeployInfoMap == null || serviceInfoAndDeployInfoMap.isEmpty())
            {
                logger.info("ServiceInfoAndDeployInfoMap is null or employ. Just return");
                return;
            }
            Object serviceInfoMapObj = serviceInfoAndDeployInfoMap.get(SERVICE_INFO_KEY);
            if (serviceInfoMapObj == null || !(serviceInfoMapObj instanceof Map))
            {
                logger.info("serviceInfoMapObj is null or not Map");
            }
            else
            {
                Map<String, Map> serviceInfoMap = (Map<String, Map>)serviceInfoMapObj;
                updateServiceInfoMap(serviceInfoMap);
            }
            
            Object deployInfoMapObj = serviceInfoAndDeployInfoMap.get(DEPLOY_INFO_KEY);
            if (deployInfoMapObj == null || !(deployInfoMapObj instanceof Map))
            {
                logger.info("deployInfoMapObj is null or not Map");
            }
            else
            {
                zNodeDepolyInfoMap = (Map<String, Object>) deployInfoMapObj;
                updateDeployInfoMap(zNodeDepolyInfoMap);
            }
        }
        finally
        {
            lock.unlock();
        }
    }
    
    @SuppressWarnings({ "rawtypes" })
    private void updateServiceInfoMap(Map<String, Map> serviceInfoMap) throws KeeperException, InterruptedException
    {
        lock.lock();
        try
        {
            if (serviceInfoMap == null || serviceInfoMap.isEmpty())
            {
                logger.info("serviceInfoMap is empty, just return");
                return;
            }
            Map<String, Map> serviceNameToServicePropertiesMapCopy = new HashMap<String, Map>(serviceNameToServicePropertiesMap);
            Map<String, Map> addZNodeServiceDataMap = new HashMap<String, Map>();
            Map<String, Map> updateZNodeServiceDataMap = new HashMap<String, Map>();
            Map<String, Map> delZNodeServiceDataMap = new HashMap<String, Map>();
            for(Map.Entry<String, Map> entry : serviceInfoMap.entrySet())
            {
                String serviceName = entry.getKey();
                Map servicePropertiesMap = entry.getValue();
                if (servicePropertiesMap == null)
                {
                    logger.error("servicePropertiesMap is null for service: " + serviceName);
                    continue;
                }
                if (serviceNameToServicePropertiesMapCopy.containsKey(serviceName))
                {
                    updateZNodeServiceDataMap.put(serviceName, servicePropertiesMap);
                }
                else
                {
                    addZNodeServiceDataMap.put(serviceName, servicePropertiesMap);
                }
            }
            
            for(Map.Entry<String, Map> entry : serviceNameToServicePropertiesMapCopy.entrySet())
            {
                String serviceName = entry.getKey();
                Map servicePropertiesMap = entry.getValue();
                if (servicePropertiesMap == null)
                {
                    logger.error("servicePropertiesMap is null for service: " + serviceName);
                    continue;
                }
                if(!serviceInfoMap.containsKey(serviceName))
                {
                    delZNodeServiceDataMap.put(serviceName, servicePropertiesMap);
                }
            }
            
            if (!addZNodeServiceDataMap.isEmpty() || !updateZNodeServiceDataMap.isEmpty() || !delZNodeServiceDataMap.isEmpty())
            {
                updateConfZNodeAndServiceProperties(addZNodeServiceDataMap, updateZNodeServiceDataMap, delZNodeServiceDataMap);
            }
        }
        finally
        {
            lock.unlock();
        }
    }
  
    @SuppressWarnings({ "rawtypes" })
    private void updateConfZNodeAndServiceProperties(Map<String, Map> addZNodeServiceDataMap, 
                        Map<String, Map> updateZNodeServiceDataMap, Map<String, Map> delZNodeServiceDataMap) throws KeeperException, InterruptedException
    {
        lock.lock();
        try
        {
            Map<String, Map> serviceNameToServicePropertiesMapCopy = new HashMap<String, Map>(serviceNameToServicePropertiesMap);
            for(Map.Entry<String, Map> entry : addZNodeServiceDataMap.entrySet())
            {
                String serviceName = entry.getKey();
                Map serviceProperties = entry.getValue();
                String serviceConfigZNodePath = getServiceConfigZNodePath(serviceName);
                boolean ret = addConfigZNode(serviceConfigZNodePath, serviceProperties);
                if (ret)
                {
                    serviceNameToServicePropertiesMapCopy.put(serviceName, serviceProperties);
                    logger.info("addConfigZNode successful for zk path: " + serviceConfigZNodePath);
                }
                else
                {
                    logger.error("addConfigZNode failed for zk path: " + serviceConfigZNodePath);
                }
            }
            for (Map.Entry<String, Map> entry : delZNodeServiceDataMap.entrySet())
            {
                String serviceName = entry.getKey();
                String serviceConfigZNodePath = getServiceConfigZNodePath(serviceName);
                boolean ret = delConfigZNode(serviceConfigZNodePath);
                if (ret)
                {
                    serviceNameToServicePropertiesMapCopy.remove(serviceName);
                    logger.info("delConfigZNode successful for zk path: " + serviceConfigZNodePath);
                }
                else
                {
                    logger.error("delConfigZNode failed for zk path: " + serviceConfigZNodePath);
                }
            }
            for (Map.Entry<String, Map> entry : updateZNodeServiceDataMap.entrySet())
            {
                String serviceName = entry.getKey();
                Map serviceProperties = entry.getValue();
                String serviceConfigZNodePath = getServiceConfigZNodePath(serviceName);
                boolean ret = updateConfigZNode(serviceConfigZNodePath, serviceProperties);
                if (ret)
                {
                    serviceNameToServicePropertiesMapCopy.put(serviceName, serviceProperties);
                    logger.info("updateConfigZNode successful for zk path: " + serviceConfigZNodePath);
                }
                else
                {
                    logger.error("updateConfigZNode failed for zk path: " + serviceConfigZNodePath);
                }
            }
            serviceNameToServicePropertiesMap = serviceNameToServicePropertiesMapCopy;
        }
        finally
        {
            lock.unlock();
        }
    }
    
    private String getServiceConfigZNodePath(String serviceName)
    {
        return confZnodePath + "/" + serviceName;
    }
    
    @SuppressWarnings("rawtypes")
    private boolean addConfigZNode(String serviceZNodePath, Map serviceProperties) throws KeeperException, InterruptedException
    {
        if (serviceZNodePath == null || serviceProperties == null)
        {
            return false;
        }
        if (ZkUtil.checkZnodeExist(zk, serviceZNodePath))
        {
            logger.error("znode " + serviceZNodePath + " has already existed");
            return false;
        }
        JSONObject servicePropertiesObj = new JSONObject(serviceProperties);
        ZkUtil.createEphemeralZNode(zk, serviceZNodePath, servicePropertiesObj);
        return true;
    }
    
    private boolean delConfigZNode(String serviceZNodePath) throws KeeperException, InterruptedException
    {
        if (serviceZNodePath == null)
        {
            return false;
        }
        if (!ZkUtil.checkZnodeExist(zk, serviceZNodePath))
        {
            logger.error("znode " + serviceZNodePath + " not existed. Can not delete it");
            return false;
        }
        ZkUtil.deleteZnode(zk, serviceZNodePath);
        return true;
    }
    
    @SuppressWarnings("rawtypes")
    private boolean updateConfigZNode(String serviceZNodePath, Map serviceProperties) throws KeeperException, InterruptedException
    {
        if (serviceZNodePath == null || serviceProperties == null)
        {
            return false;
        }
        if (!ZkUtil.checkZnodeExist(zk, serviceZNodePath))
        {
            logger.error("znode " + serviceZNodePath + " not existed. Can not update it");
            return false;
        }
        JSONObject servicePropertiesObj = new JSONObject(serviceProperties);
        ZkUtil.setData(zk, serviceZNodePath, servicePropertiesObj);
        return true;
    }
    
    private void updateDeployInfoMap(Map<String, Object> deployInfoMap) throws KeeperException, InterruptedException
    {
        if (deployInfoMap == null)
        {
            logger.info("deploy info is null. Just return");
            return;
        }
        boolean ret = addOrUpdateConfigZNode(getDeployChildZnodePath(), deployInfoMap);
        if (ret)
        {
            logger.info("Update the Deploy ZNode successful");
            zNodeDepolyInfoMap = deployInfoMap;
        }
        else
        {
            logger.error("Update the Deploy ZNode failed");
        }
    }
    
    private String getDeployChildZnodePath()
    {
        return confZnodePath + "/" + deployServiceName;
    }
    
    @SuppressWarnings("rawtypes")
    private boolean addOrUpdateConfigZNode(String serviceZNodePath, Map serviceProperties) throws KeeperException, InterruptedException
    {
        if (serviceZNodePath == null || serviceProperties == null)
        {
            return false;
        }
        
        JSONObject servicePropertiesObj = new JSONObject(serviceProperties);
        if (ZkUtil.checkZnodeExist(zk, serviceZNodePath))
        {
            ZkUtil.setData(zk, serviceZNodePath, servicePropertiesObj);
        }
        else
        {
            ZkUtil.createEphemeralZNode(zk, serviceZNodePath, servicePropertiesObj);
        }
        return true;
    }
    
    private boolean isServiceReadyToWork()
    {
        return isRunning;
    }
    
    class ZkWatcher implements Watcher
    {
        @Override
        public void process(WatchedEvent event)
        {
            logger.debug("Zookeeper event is: " + event);
        } 
    }
}
