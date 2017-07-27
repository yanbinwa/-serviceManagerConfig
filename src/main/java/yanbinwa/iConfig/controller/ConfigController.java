package yanbinwa.iConfig.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import yanbinwa.common.exceptions.ServiceUnavailableException;
import yanbinwa.iConfig.service.ConfigService;

@RestController
@RequestMapping("/config")
public class ConfigController
{
    @Autowired
    ConfigService configService;
    
    @RequestMapping(value="/getServiceName",method=RequestMethod.GET)
    public String getServiceName() throws ServiceUnavailableException
    {
        return configService.getServiceName();
    }
}
