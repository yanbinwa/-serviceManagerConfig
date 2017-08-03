package yanbinwa.iConfig.service;

import org.springframework.beans.factory.InitializingBean;

import yanbinwa.common.exceptions.ServiceUnavailableException;
import yanbinwa.common.iInterface.ConfigServiceIf;
import yanbinwa.common.iInterface.ServiceLifeCycle;

public interface ConfigService extends InitializingBean, ServiceLifeCycle, ConfigServiceIf
{
    public static final String SERVICE_INFO_KEY = "servicesInfo";
    public static final String COMPONENTS_KEY = "components";
    public static final String DEPLOY_INFO_KEY = "deployInfo";
    public static final String DEPLOY_SERVICE_KEY = "deployService";
    
    public static final int ZK_WAIT_INTERVAL = 10 * 1000;
    
    String getServiceName() throws ServiceUnavailableException;

    void startManageService();

    void stopManageService();
}
