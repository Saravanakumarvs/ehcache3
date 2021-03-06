/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache;

import org.ehcache.config.BaseCacheConfiguration;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.Configuration;
import org.ehcache.config.DefaultConfiguration;
import org.ehcache.config.ResourcePool;
import org.ehcache.config.ResourceType;
import org.ehcache.config.RuntimeConfiguration;
import org.ehcache.config.StoreConfigurationImpl;
import org.ehcache.event.CacheEventListener;
import org.ehcache.event.CacheEventListenerConfiguration;
import org.ehcache.event.CacheEventListenerProvider;
import org.ehcache.events.CacheEventDispatcherFactory;
import org.ehcache.events.CacheEventDispatcher;
import org.ehcache.events.CacheManagerListener;
import org.ehcache.exceptions.CachePersistenceException;
import org.ehcache.spi.LifeCycled;
import org.ehcache.spi.LifeCycledAdapter;
import org.ehcache.spi.ServiceLocator;
import org.ehcache.spi.cache.Store;
import org.ehcache.spi.loaderwriter.CacheLoaderWriter;
import org.ehcache.spi.loaderwriter.CacheLoaderWriterProvider;
import org.ehcache.spi.loaderwriter.WriteBehindConfiguration;
import org.ehcache.spi.loaderwriter.WriteBehindProvider;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.UnsupportedTypeException;
import org.ehcache.spi.service.CacheManagerProviderService;
import org.ehcache.spi.service.ServiceConfiguration;
import org.ehcache.spi.service.LocalPersistenceService;
import org.ehcache.spi.service.LocalPersistenceService.PersistenceSpaceIdentifier;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceCreationConfiguration;
import org.ehcache.spi.service.ServiceDependencies;
import org.ehcache.util.ClassLoading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.ehcache.config.ResourceType.Core.DISK;
import static org.ehcache.config.ResourceType.Core.OFFHEAP;

/**
 * @author Alex Snaps
 */
public class EhcacheManager implements PersistentCacheManager {

  @ServiceDependencies({ Store.Provider.class,
      CacheLoaderWriterProvider.class,
      WriteBehindProvider.class,
      CacheEventDispatcherFactory.class,
      CacheEventListenerProvider.class })
  private static class ServiceDeps {
    private ServiceDeps() {
      throw new UnsupportedOperationException("This is an annotation placeholder, not to be instantiated");
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(EhcacheManager.class);

  private final StatusTransitioner statusTransitioner = new StatusTransitioner(LOGGER);

  private final ServiceLocator serviceLocator;
  private final boolean useLoaderInAtomics;
  private final DefaultConfiguration configuration;

  private final ConcurrentMap<String, CacheHolder> caches = new ConcurrentHashMap<String, CacheHolder>();
  private final ClassLoader cacheManagerClassLoader;

  private final CopyOnWriteArrayList<CacheManagerListener> listeners = new CopyOnWriteArrayList<CacheManagerListener>();

  public EhcacheManager(Configuration config) {
    this(config, Collections.<Service>emptyList(), true);
  }

  public EhcacheManager(Configuration config, Collection<Service> services) {
    this(config, services, true);
  }
  public EhcacheManager(Configuration config, Collection<Service> services, boolean useLoaderInAtomics) {
    this.serviceLocator = new ServiceLocator(services.toArray(new Service[services.size()]));
    this.useLoaderInAtomics = useLoaderInAtomics;
    this.cacheManagerClassLoader = config.getClassLoader() != null ? config.getClassLoader() : ClassLoading.getDefaultClassLoader();
    this.configuration = new DefaultConfiguration(config);
  }

  @Override
  public <K, V> Cache<K, V> getCache(String alias, Class<K> keyType, Class<V> valueType) {
    statusTransitioner.checkAvailable();
    final CacheHolder cacheHolder = caches.get(alias);
    if(cacheHolder == null) {
      return null;
    } else {
      try {
        return cacheHolder.retrieve(keyType, valueType);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Cache '" + alias + "' type is <" + cacheHolder.keyType.getName() + ", "
                                           + cacheHolder.valueType.getName() + ">, but you retrieved it with <"
                                           + keyType.getName() + ", " + valueType.getName() +">");
      }
    }
  }

  @Override
  public void removeCache(final String alias) {
    removeCache(alias, true);
  }

  private void removeCache(final String alias, final boolean removeFromConfig) {
    statusTransitioner.checkAvailable();
    final CacheHolder cacheHolder = caches.remove(alias);
    if(cacheHolder != null) {
      final Ehcache<?, ?> ehcache = cacheHolder.retrieve(cacheHolder.keyType, cacheHolder.valueType);
      if(!statusTransitioner.isTransitioning()) {
        for (CacheManagerListener listener : listeners) {
          listener.cacheRemoved(alias, ehcache);
        }
      }
      closeEhcache(alias, ehcache);
      if (removeFromConfig) {
        configuration.removeCacheConfiguration(alias);
      }
      LOGGER.info("Cache '{}' is removed from EhcacheManager.", alias);
    }
  }

  void closeEhcache(final String alias, final Ehcache<?, ?> ehcache) {
    boolean diskTransient = isDiskTransient(ehcache);
    ehcache.close();
    if (diskTransient) {
      try {
        destroyPersistenceSpace(alias);
      } catch (CachePersistenceException e) {
        LOGGER.debug("Unable to clear persistence space for cache {}", alias, e);
      }
    }
    LOGGER.info("Cache '{}' is closed from EhcacheManager.", alias);
  }

  private boolean isDiskTransient(Ehcache<?, ?> ehcache) {
    boolean diskTransient = false;
    ResourcePool diskResource = ehcache.getRuntimeConfiguration()
      .getResourcePools()
      .getPoolForResource(ResourceType.Core.DISK);
    if (diskResource != null) {
      diskTransient = !diskResource.isPersistent();
    }
    return diskTransient;
  }

  @Override
  public <K, V> Cache<K, V> createCache(final String alias, CacheConfiguration<K, V> config) throws IllegalArgumentException {
    return createCache(alias, config, true);
  }

  private <K, V> Cache<K, V> createCache(final String alias, CacheConfiguration<K, V> originalConfig, boolean addToConfig) throws IllegalArgumentException {
    statusTransitioner.checkAvailable();

    LOGGER.info("Cache '{}' is getting created in EhcacheManager.", alias);

    CacheConfiguration<K, V> config = adjustConfigurationWithCacheManagerDefaults(originalConfig);
    Class<K> keyType = config.getKeyType();
    Class<V> valueType = config.getValueType();

    final CacheHolder value = new CacheHolder(keyType, valueType, null);
    if (caches.putIfAbsent(alias, value) != null) {
      throw new IllegalArgumentException("Cache '" + alias +"' already exists");
    }

    Ehcache<K, V> cache = null;

    RuntimeException failure = null;
    try {
      cache = createNewEhcache(alias, config, keyType, valueType);
      cache.init();
      if (addToConfig) {
        configuration.addCacheConfiguration(alias, cache.getRuntimeConfiguration());
      } else {
        configuration.replaceCacheConfiguration(alias, originalConfig, cache.getRuntimeConfiguration());
      }
    } catch (RuntimeException e) {
      failure = e;
    }

    if(failure == null) {
      try {
        if(!statusTransitioner.isTransitioning()) {
          for (CacheManagerListener listener : listeners) {
            listener.cacheAdded(alias, cache);
          }
        }
      } finally {
        value.setCache(cache);
      }
    } else {
      caches.remove(alias);
      value.setCache(null);
      throw new IllegalStateException("Cache '"+alias+"' creation in EhcacheManager failed.", failure);
    }
    LOGGER.info("Cache '{}' created in EhcacheManager.", alias);
    return cache;
  }

  /**
   *  adjusts the config to reflect new classloader & serialization provider
   */
  private <K, V> CacheConfiguration<K, V> adjustConfigurationWithCacheManagerDefaults(CacheConfiguration<K, V> config) {
    ClassLoader cacheClassLoader = config.getClassLoader();
    if (cacheClassLoader == null) {
      cacheClassLoader = cacheManagerClassLoader;
    }
    if (cacheClassLoader != config.getClassLoader() ) {
      config = new BaseCacheConfiguration<K, V>(config.getKeyType(), config.getValueType(),
          config.getEvictionVeto(), config.getEvictionPrioritizer(), cacheClassLoader, config.getExpiry(),
          config.getResourcePools(), config.getServiceConfigurations().toArray(
          new ServiceConfiguration<?>[config.getServiceConfigurations().size()]));
    }
    return config;
  }

  <K, V> Ehcache<K, V> createNewEhcache(final String alias, final CacheConfiguration<K, V> config,
                                        final Class<K> keyType, final Class<V> valueType) {
    Collection<ServiceConfiguration<?>> adjustedServiceConfigs = new ArrayList<ServiceConfiguration<?>>(config.getServiceConfigurations());
    ServiceConfiguration[] serviceConfigs = adjustedServiceConfigs.toArray(new ServiceConfiguration[adjustedServiceConfigs.size()]);

    List<ServiceConfiguration> unknownServiceConfigs = new ArrayList<ServiceConfiguration>();
    for (ServiceConfiguration serviceConfig : serviceConfigs) {
      if (!serviceLocator.knowsServiceFor(serviceConfig)) {
        unknownServiceConfigs.add(serviceConfig);
      }
    }
    if (!unknownServiceConfigs.isEmpty()) {
      throw new IllegalStateException("Cannot find service(s) that can handle following configuration(s) : " + unknownServiceConfigs);
    }

    List<LifeCycled> lifeCycledList = new ArrayList<LifeCycled>();

    final Store.Provider storeProvider = serviceLocator.getService(Store.Provider.class);
    if (config.getResourcePools().getResourceTypeSet().contains(ResourceType.Core.DISK)) {
      LocalPersistenceService persistenceService = serviceLocator.getService(LocalPersistenceService.class);
      
      if (persistenceService == null) {
        throw new IllegalStateException("No LocalPersistenceService could be found - did you configure it at the CacheManager level?");
      }

      if (!config.getResourcePools().getPoolForResource(ResourceType.Core.DISK).isPersistent()) {
        try {
          persistenceService.destroyPersistenceSpace(alias);
        } catch (CachePersistenceException cpex) {
          throw new RuntimeException("Unable to clean-up persistence space for non-restartable cache " + alias, cpex);
        }
      }
      try {
        PersistenceSpaceIdentifier space = persistenceService.getOrCreatePersistenceSpace(alias);
        serviceConfigs = Arrays.copyOf(serviceConfigs, serviceConfigs.length + 1);
        serviceConfigs[serviceConfigs.length - 1] = space;
      } catch (CachePersistenceException cpex) {
        throw new RuntimeException("Unable to create persistence space for cache " + alias, cpex);
      }
    }
    Serializer<K> keySerializer = null;
    Serializer<V> valueSerializer = null;
    final SerializationProvider serialization = serviceLocator.getService(SerializationProvider.class);
    Set<ResourceType> resources = config.getResourcePools().getResourceTypeSet();
    if (serialization != null) {
      try {
        final Serializer<K> keySer = serialization.createKeySerializer(keyType, config.getClassLoader(), serviceConfigs);
        lifeCycledList.add(new LifeCycledAdapter() {
          @Override
          public void close() throws Exception {
            serialization.releaseSerializer(keySer);
          }
        });
        keySerializer = keySer;
      } catch (UnsupportedTypeException e) {
        if (resources.contains(DISK) || resources.contains(OFFHEAP)) {
          throw new RuntimeException(e);
        } else {
          LOGGER.debug("Could not create serializers for " + alias, e);
        }
      }
      try {
        final Serializer<V> valueSer = serialization.createValueSerializer(valueType, config.getClassLoader(), serviceConfigs);
        lifeCycledList.add(new LifeCycledAdapter() {
          @Override
          public void close() throws Exception {
            serialization.releaseSerializer(valueSer);
          }
        });
        valueSerializer = valueSer;
      } catch (UnsupportedTypeException e) {
        if (resources.contains(DISK) || resources.contains(OFFHEAP)) {
          throw new RuntimeException(e);
        } else {
          LOGGER.debug("Could not create serializers for " + alias, e);
        }
      }
    }
    Store.Configuration<K, V> storeConfiguration = new StoreConfigurationImpl<K, V>(config, keySerializer, valueSerializer);
    final Store<K, V> store = storeProvider.createStore(storeConfiguration, serviceConfigs);

    lifeCycledList.add(new LifeCycled() {
      @Override
      public void init() throws Exception {
        storeProvider.initStore(store);
      }

      @Override
      public void close() {
        storeProvider.releaseStore(store);
      }
    });

    final CacheLoaderWriterProvider cacheLoaderWriterProvider = serviceLocator.getService(CacheLoaderWriterProvider.class);
    final CacheLoaderWriter<? super K, V> loaderWriter;
    final CacheLoaderWriter<? super K, V> decorator ;
    if(cacheLoaderWriterProvider != null) {
      loaderWriter = cacheLoaderWriterProvider.createCacheLoaderWriter(alias, config);
      WriteBehindConfiguration writeBehindConfiguration = ServiceLocator.findSingletonAmongst(WriteBehindConfiguration.class, config.getServiceConfigurations().toArray());
      if(writeBehindConfiguration == null) {
        decorator = loaderWriter;
      } else {
        final WriteBehindProvider factory = serviceLocator.getService(WriteBehindProvider.class);
        decorator = factory.createWriteBehindLoaderWriter(loaderWriter, writeBehindConfiguration);
        if(decorator != null) {
          lifeCycledList.add(new LifeCycledAdapter() {
            @Override
            public void close() {
              factory.releaseWriteBehindLoaderWriter(decorator);
            }
          });
        }
      }
      
      if (loaderWriter != null) {
        lifeCycledList.add(new LifeCycledAdapter() {
          @Override
          public void close() throws Exception {
            cacheLoaderWriterProvider.releaseCacheLoaderWriter(loaderWriter);
          }
        });
      }
    } else {
      loaderWriter = null;
      decorator = null;
    }

    final CacheEventDispatcherFactory cenlProvider = serviceLocator.getService(CacheEventDispatcherFactory.class);
    final CacheEventDispatcher<K, V> evtService = cenlProvider.createCacheEventDispatcher(store, serviceConfigs);
    lifeCycledList.add(new LifeCycledAdapter() {
      @Override
      public void close() {
        cenlProvider.releaseCacheEventDispatcher(evtService);
      }
      
    });
    
    final Ehcache<K, V> ehCache = new Ehcache<K, V>(config, store, decorator, evtService,
        useLoaderInAtomics, LoggerFactory.getLogger(Ehcache.class + "-" + alias));

    final CacheEventListenerProvider evntLsnrFactory = serviceLocator.getService(CacheEventListenerProvider.class);
    if (evntLsnrFactory != null) {
      Collection<CacheEventListenerConfiguration> evtLsnrConfigs =
      ServiceLocator.findAmongst(CacheEventListenerConfiguration.class, config.getServiceConfigurations().toArray());
      for (CacheEventListenerConfiguration lsnrConfig: evtLsnrConfigs) {
        final CacheEventListener<K, V> lsnr = evntLsnrFactory.createEventListener(alias, lsnrConfig);
        if (lsnr != null) {
          ehCache.getRuntimeConfiguration().registerCacheEventListener(lsnr, lsnrConfig.orderingMode(), lsnrConfig.firingMode(),
          lsnrConfig.fireOn());
          lifeCycledList.add(new LifeCycled() {
            @Override
            public void init() throws Exception {
              // no-op for now
            }

            @Override
            public void close() throws Exception {
              evntLsnrFactory.releaseEventListener(lsnr);
            }
          });
        }
      }
      evtService.setStoreListenerSource(ehCache);
    }

    for (LifeCycled lifeCycled : lifeCycledList) {
      ehCache.addHook(lifeCycled);
    }

    return ehCache;
  }

  public void registerListener(CacheManagerListener listener) {
    if(!listeners.contains(listener)) {
      listeners.add(listener);
      statusTransitioner.registerListener(listener);
    }
  }

  public void deregisterListener(CacheManagerListener listener) {
    if(listeners.remove(listener)) {
      statusTransitioner.deregisterListener(listener);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void init() {
    final StatusTransitioner.Transition st = statusTransitioner.init();

    if (serviceLocator.getService(CacheManagerProviderService.class) == null) {
      this.serviceLocator.addService(new DefaultCacheManagerProviderService(this));
    }

    try {
      for (ServiceCreationConfiguration<? extends Service> serviceConfig : configuration.getServiceCreationConfigurations()) {
        Service service = serviceLocator.getOrCreateServiceFor(serviceConfig);
        if (service == null) {
          throw new IllegalArgumentException("Couldn't resolve Service " + serviceConfig.getServiceType().getName());
        }
      }
      serviceLocator.loadDependenciesOf(ServiceDeps.class);
      try {
        serviceLocator.startAllServices();
      } catch (Exception e) {
        throw st.failed(e);
      }

      Deque<String> initiatedCaches = new ArrayDeque<String>();
      try {
        for (Entry<String, CacheConfiguration<?, ?>> cacheConfigurationEntry : configuration.getCacheConfigurations()
            .entrySet()) {
          final String alias = cacheConfigurationEntry.getKey();
          createCache(alias, cacheConfigurationEntry.getValue(), false);
          initiatedCaches.push(alias);
        }
      } catch (RuntimeException e) {
        while (!initiatedCaches.isEmpty()) {
          String toBeClosed = initiatedCaches.pop();
          try {
            removeCache(toBeClosed, false);
          } catch (Exception exceptionClosingCache) {
              LOGGER.error("Cache '{}' could not be removed due to ", toBeClosed, exceptionClosingCache);
          }
        }
        try {
          serviceLocator.stopAllServices();
        } catch (Exception exceptionStoppingServices) {
          LOGGER.error("Stopping services failed due to ", exceptionStoppingServices);
        }
        throw e;
      }
    } catch (Exception e) {
      throw st.failed(e);
    }
    st.succeeded();
  }

  @Override
  public Status getStatus() {
    return statusTransitioner.currentStatus();
  }

  @Override
  public void close() {
    final StatusTransitioner.Transition st = statusTransitioner.close();

    Exception firstException = null;
    try {
      for (String alias : caches.keySet()) {
        try {
          removeCache(alias, false);
        } catch (Exception e) {
          if(firstException == null) {
            firstException = e;
          } else {
            LOGGER.error("Cache '{}' could not be removed due to ", alias, e);
          }
        }
      }

      serviceLocator.stopAllServices();
    } catch (Exception e) {
      if(firstException == null) {
        firstException = e;
      }
    }
    if(firstException != null) {
      throw st.failed(firstException);
    }
    st.succeeded();
  }

  @Override
  public RuntimeConfiguration getRuntimeConfiguration() {
    return configuration;
  }

  @Override
  public Maintainable toMaintenance() {
    final StatusTransitioner.Transition st = statusTransitioner.maintenance();
    startPersistenceService();
    try {
      final Maintainable maintainable = new Maintainable() {
        private LocalPersistenceService persistenceService = serviceLocator.getService(LocalPersistenceService.class);

        @Override
        public void create() {
          EhcacheManager.this.create();
        }

        @Override
        public void destroy() {
          EhcacheManager.this.destroy();
          persistenceService.destroyAllPersistenceSpaces();
        }

        @Override
        public void close() {
          persistenceService.stop();
          statusTransitioner.exitMaintenance().succeeded();
        }
      };
      st.succeeded();
      return maintainable;
    } catch (RuntimeException e) {
      throw st.failed(e);
    }
  }

  private LocalPersistenceService startPersistenceService() {
    LocalPersistenceService persistenceService = serviceLocator.getService(LocalPersistenceService.class);
    persistenceService.start(serviceLocator);
    return persistenceService;
  }

  void create() {
    statusTransitioner.checkMaintenance();
  }

  void destroy() {
    statusTransitioner.checkMaintenance();
  }

  @Override
  public void destroyCache(final String alias) throws CachePersistenceException {
    LOGGER.info("Destroying Cache '{}' in EhcacheManager.", alias);
    final CacheHolder cacheHolder = caches.remove(alias);
    if(cacheHolder != null) {
      final Ehcache<?, ?> ehcache = cacheHolder.retrieve(cacheHolder.keyType, cacheHolder.valueType);
      if(ehcache.getStatus() == Status.AVAILABLE) {
        ehcache.close();
      }
    }
    destroyPersistenceSpace(alias);
    LOGGER.info("Cache '{}' is successfully destroyed in EhcacheManager.", alias);
  }

  private void destroyPersistenceSpace(String alias) throws CachePersistenceException {
    LocalPersistenceService persistenceService = serviceLocator.getService(LocalPersistenceService.class);
    persistenceService.destroyPersistenceSpace(alias);
  }

  // for tests at the moment
  ClassLoader getClassLoader() {
    return cacheManagerClassLoader;
  }

  private static final class CacheHolder {
    private final Class<?> keyType;
    private final Class<?> valueType;
    private volatile Ehcache<?, ?> cache;
    private volatile boolean isValueSet = false;

    CacheHolder(Class<?> keyType, Class<?> valueType, Ehcache<?, ?> cache) {
      this.keyType = keyType;
      this.valueType = valueType;
      this.cache = cache;
    }

    <K, V> Ehcache<K, V> retrieve(Class<K> refKeyType, Class<V> refValueType) {
      if (!isValueSet) {
        synchronized (this) {
          boolean interrupted = false;
          try {
            while(!isValueSet) {
              try {
                wait();
              } catch (InterruptedException e) {
                interrupted = true;
              }
            }
          } finally {
            if(interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
      if (keyType == refKeyType && valueType == refValueType) {
        return cast(cache);
      } else {
        throw new IllegalArgumentException();
      }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Ehcache<K, V> cast(Ehcache<?, ?> cache) {
      return (Ehcache<K, V>)cache;
    }

    public synchronized void setCache(final Ehcache<?, ?> cache) {
      this.cache = cache;
      this.isValueSet = true;
      notifyAll();
    }
  }
}