/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.nutch.api.impl;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.api.ConfManager;
import org.apache.nutch.api.NutchServer;
import org.apache.nutch.api.model.request.NutchConfig;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.util.NutchConfiguration;
import org.slf4j.Logger;

import com.google.common.collect.Maps;

public class RAMConfManager implements ConfManager {

  public static final Logger LOG = NutchServer.LOG;

  private Map<String, Configuration> configurations = Maps.newConcurrentMap();

  private AtomicInteger configSequence = new AtomicInteger();

  public RAMConfManager() {
    configurations.put(ConfManager.DEFAULT, NutchConfiguration.create());
  }

  public Set<String> list() {
    return configurations.keySet();
  }

  @Override
  public String create(NutchConfig nutchConfig) {
    if (StringUtils.isBlank(nutchConfig.getConfigId())) {
      nutchConfig.setConfigId(generateId(nutchConfig));
    }

    // LOG.info("Try to create nutch config : " + nutchConfig.toString());

    if (!canCreate(nutchConfig)) {
      return null;
    }

    createHadoopConfig(nutchConfig);

    if (LOG.isInfoEnabled()) {
      LOG.info("Created a new NutchConfig, #" + nutchConfig.getConfigId());
    }

    return nutchConfig.getConfigId();
  }

  public Configuration getDefault() {
    return configurations.get(ConfManager.DEFAULT);
  }

  public Configuration get(String confId) {
    if (confId == null) {
      return configurations.get(ConfManager.DEFAULT);
    }
    return configurations.get(confId);
  }

  public Map<String, String> getAsMap(String confId) {
    Configuration configuration = configurations.get(confId);
    if (configuration == null) {
      return Collections.emptyMap();
    }

    Iterator<Entry<String, String>> iterator = configuration.iterator();
    Map<String, String> configMap = Maps.newTreeMap();
    while (iterator.hasNext()) {
      Entry<String, String> entry = iterator.next();
      configMap.put(entry.getKey(), entry.getValue());
    }

    return configMap;
  }

  public void setProperty(String confId, String propName, String propValue) {
    if (!configurations.containsKey(confId)) {
      throw new IllegalArgumentException("Unknown configId <" + confId + ">");
    }

    Configuration conf = configurations.get(confId);
    conf.set(propName, propValue);
  }

  public void delete(String confId) {
    configurations.remove(confId);

    if (LOG.isInfoEnabled()) {
      LOG.info("Removed a NutchConfig, #" + confId);
    }
  }

  /**
   * Add a qiwu ui specified part to construct the id
   * */
  private String generateId(NutchConfig nutchConfig) {
    String configId = String.valueOf(configSequence.incrementAndGet());
    String uiCrawlId = nutchConfig.getParams().get(Nutch.UI_CRAWL_ID);

    return MessageFormat.format("{0}-{1}-{2}", uiCrawlId, nutchConfig.getPriority(), configId);
  }

  private boolean canCreate(NutchConfig nutchConfig) {
    if (nutchConfig.isForce()) {
      return true;
    }

    if (!configurations.containsKey(nutchConfig.getConfigId())) {
      return true;
    }

    return false;
  }

  private void createHadoopConfig(NutchConfig nutchConfig) {
    Configuration conf = NutchConfiguration.create();

    conf.set("nutch.config.id", nutchConfig.getConfigId());

    configurations.put(nutchConfig.getConfigId(), conf);

    if (MapUtils.isEmpty(nutchConfig.getParams())) {
      return;
    }

    for (Entry<String, String> e : nutchConfig.getParams().entrySet()) {
      conf.set(e.getKey(), e.getValue());
    }
  }

  private boolean isMaster() {
    Configuration conf = get(ConfManager.DEFAULT);
    String host = conf.get("nutch.master.hostname", "master");

    try {
      String hostname = FileUtils.readFileToString(new File("/etc/hostname")).trim();

      // update only if this is not the master
      if (hostname.equalsIgnoreCase(host)) {
        return true;
      }
    } catch (IOException e) {
      LOG.error(e.toString());
    }

    return false;
  }
}
