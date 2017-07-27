package yanbinwa.iConfig.service;

import org.springframework.beans.factory.InitializingBean;

import yanbinwa.common.exceptions.ServiceUnavailableException;
import yanbinwa.common.iInterface.ServiceLifeCycle;

public interface ConfigService extends InitializingBean, ServiceLifeCycle 
{
    public static final String SERVICE_INFO_KEY = "servicesInfo";
    public static final String COMPONENTS_KEY = "components";
    public static final String DEPLOY_INFO_KEY = "deployInfo";
    
    public static final int ZK_WAIT_INTERVAL = 10 * 1000;
    
    String getServiceName() throws ServiceUnavailableException;
<<<<<<< HEAD
        
=======
    
    boolean isServiceReady() throws ServiceUnavailableException;
    
>>>>>>> 2aaaa152c3b5e0b79686600e802c099dfee3bc39
    void startManageService();

    void stopManageService();
}
