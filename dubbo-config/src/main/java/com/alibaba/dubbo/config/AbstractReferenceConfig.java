/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.ExtensionLoader;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcConstants;
import com.alibaba.dubbo.rpc.cluster.Cluster;

/**
 * AbstractDefaultConfig
 * 
 * @author william.liangf
 */
public abstract class AbstractReferenceConfig extends AbstractMethodConfig {

    private static final long      serialVersionUID = -1559314110797223229L;

    // 服务接口的本地实现类名
    protected String               local;

    // 服务接口的本地实现类名
    protected String               stub;

    // 服务接口的失败mock实现类名
    protected String               mock;

    // 服务监控
    protected MonitorConfig        monitor;
    
    // 代理类型
    protected String               proxy;
    
    // 集群方式
    protected String               cluster;

    // 过滤器
    protected String               filter;
    
    // 监听器
    protected String               listener;

    // 负责人
    protected String               owner;

    // 连接数限制,0表示共享连接，否则为该服务独享连接数
    protected Integer              connections;
    
    // 连接数限制
    protected String               layer;
    
    // 应用信息
    protected ApplicationConfig    application;

    // 注册中心
    protected List<RegistryConfig> registries;
    
    // callback实例个数限制
    private Integer                callbacks;
    
    // 连接事件
    protected String              onconnect;
    
    // 断开事件
    protected String              ondisconnect;

    protected void checkRegistry() {
        // 兼容旧版本
        if (registries == null || registries.size() == 0) {
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                registries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registries.add(registryConfig);
                }
            }
        }
        if (registries == null || registries.size() == 0) {
            throw new IllegalStateException((getClass().getSimpleName().startsWith("Reference") 
                    ? "No such any registry to refer service in consumer " 
                        : "No such any registry to export service in provider ")
                                                    + NetUtils.getLocalHost()
                                                    + " use dubbo version "
                                                    + Version.getVersion()
                                                    + ", Please add <dubbo:registry address=\"...\" /> to your spring config. If you want unregister, please set <dubbo:service registry=\"N/A\" />");
        }
    }

    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // 兼容旧版本
        if (application == null) {
            String app = ConfigUtils.getProperty("dubbo.application.name");
            if (app != null && app.length() > 0) {
                application = new ApplicationConfig();
                application.setName(app);
            }
        }
        if (application == null) {
            throw new IllegalStateException(
                                            "No such application config! Please add <dubbo:application name=\"...\" /> to your spring config.");
        }
        
        
        String wait = ConfigUtils.getProperty(RpcConstants.SHUTDOWN_TIMEOUT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(RpcConstants.SHUTDOWN_TIMEOUT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(RpcConstants.SHUTDOWN_TIMEOUT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(RpcConstants.SHUTDOWN_TIMEOUT_SECONDS_KEY, wait.trim());
            }
        }
    }
    
    protected List<URL> loadRegistries() {
        checkRegistry();
        List<URL> registryList = new ArrayList<URL>();
        if (registries != null && registries.size() > 0) {
            for (RegistryConfig config : registries) {
                if (! RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(config.getAddress())) {
                    if (config.getAddress() == null || config.getAddress().length() == 0) {
                        throw new IllegalStateException("registry address == null");
                    }
                    Map<String, String> map = new HashMap<String, String>();
                    appendParameters(map, application);
                    appendParameters(map, config);
                    map.put("path", RegistryService.class.getName());
                    if (! map.containsKey("protocol")) {
                        if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                            map.put("protocol", "remote");
                        } else {
                            map.put("protocol", "dubbo");
                        }
                    }
                    List<URL> urls = UrlUtils.parseURLs(config.getAddress(), map);
                    for (URL url : urls) {
                        url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                        url = url.setProtocol(Constants.REGISTRY_PROTOCOL);
                        registryList.add(url);
                    }
                }
            }
        }
        return registryList;
    }
    
    protected URL loadMonitor(URL registryURL) {
        if (monitor == null) {
            return null;
        }
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        appendParameters(map, monitor);
        if (ConfigUtils.isNotEmpty(monitor.getAddress())) {
            if (! map.containsKey(Constants.PROTOCOL_KEY)) {
                if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, "dubbo");
                }
            }
            return UrlUtils.parseURL(monitor.getAddress(), map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(RpcConstants.REFER_KEY, StringUtils.toQueryString(map));
        }
        return null;
    }

    /**
     * @deprecated Replace to <code>getStub()</code>
     * @return
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @deprecated Replace to <code>setStub(String)</code>
     * @param local
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }
    
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(String.valueOf(local));
        }
    }
    
    public String getMock() {
        return mock;
    }

    public void setMock(String mock) {
        checkName("mock", mock);
        this.mock = mock;
    }
    
    public void setMock(Boolean mock) {
        if (mock == null) {
            setMock((String) null);
        } else {
            setMock(String.valueOf(mock));
        }
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }
    
    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }
    
    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        return listener;
    }
    
    public void setListener(String listener) {
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasColon("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }

    public RegistryConfig getRegistry() {
        return registries == null || registries.size() == 0 ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        this.registries = registries;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({ "unchecked" })
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>)registries;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = new MonitorConfig(monitor);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }
}