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
package org.apache.nutch.api;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import javax.ws.rs.core.Application;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.nutch.api.impl.JobFactory;
import org.apache.nutch.api.impl.JobWorkerPoolExecutor;
import org.apache.nutch.api.impl.RAMConfManager;
import org.apache.nutch.api.impl.RAMJobManager;
import org.apache.nutch.api.misc.ErrorStatusService;
import org.apache.nutch.api.model.response.JobInfo;
import org.apache.nutch.api.model.response.JobInfo.State;
import org.apache.nutch.api.resources.AdminResource;
import org.apache.nutch.api.resources.ConfigResource;
import org.apache.nutch.api.resources.DbResource;
import org.apache.nutch.api.resources.JobResource;
import org.apache.nutch.api.resources.PortResource;
import org.apache.nutch.api.resources.ProxyResource;
import org.apache.nutch.api.resources.SeedResource;
import org.apache.nutch.api.resources.ServiceResource;
import org.apache.nutch.client.NutchClient;
import org.apache.nutch.storage.local.SpringConfiguration;
import org.apache.nutch.storage.local.model.ServerInstance;
import org.apache.nutch.storage.local.service.BrowserInstanceService;
import org.apache.nutch.storage.local.service.ServerInstanceService;
import org.apache.nutch.util.NetUtil;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.ext.jaxrs.JaxRsApplication;
import org.restlet.resource.ClientResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

public class NutchServer extends Application {
  public static final String NUTCH_SERVER = "NUTCH_SERVER";

  public static final Logger LOG = LoggerFactory.getLogger(NutchServer.class);

  private static final String LOCALHOST = "localhost";
  private static final String DEFAULT_LOG_LEVEL = "INFO";
  private static final Integer DEFAULT_PORT = 8182;
  private static final int JOB_CAPACITY = 100;

  private static String logLevel = DEFAULT_LOG_LEVEL;
  private static Integer port = DEFAULT_PORT;

  private static final String CMD_HELP = "help";
  private static final String CMD_STOP = "stop";
  private static final String CMD_PORT = "port";
  private static final String CMD_LOG_LEVEL = "log";

  private ApplicationContext springContext;
  private Component component;
  private Configuration conf;
  private ConfManager configManager;
  private JobManager jobManager;
  private long startTime;

  private static AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Public constructor which accepts the port we wish to run the server on as
   * well as the logging granularity. If the latter option is not provided via
   * {@link org.apache.nutch.api.NutchServer#main(String[])} then it defaults to
   * 'INFO' however best attempts should always be made to specify a logging
   * level.
   */
  public NutchServer() {
    // Hadoop configuration
    configManager = new RAMConfManager();
    conf = configManager.getDefault();

    // Spring configuration
    springContext = new AnnotationConfigApplicationContext(SpringConfiguration.class);
    ServerInstanceService serverInstanceService = springContext.getBean(ServerInstanceService.class);
    serverInstanceService.truncate();
    BrowserInstanceService browserInstanceService = springContext.getBean(BrowserInstanceService.class);
    browserInstanceService.truncate();

    // Nutch job container
    BlockingQueue<Runnable> runnables = Queues.newArrayBlockingQueue(JOB_CAPACITY);
    JobWorkerPoolExecutor executor = new JobWorkerPoolExecutor(10, JOB_CAPACITY, 1, TimeUnit.HOURS, runnables);
    jobManager = new RAMJobManager(new JobFactory(), executor, configManager);

    // Nutch server
    // Create a new Component.
    component = new Component();
    component.getLogger().setLevel(Level.parse(logLevel));

    // Add a new HTTP server listening on defined port.
    port = conf.getInt("nutch.server.port", DEFAULT_PORT);
    component.getServers().add(Protocol.HTTP, port);

    Context childContext = component.getContext().createChildContext();
    JaxRsApplication application = new JaxRsApplication(childContext);
    application.add(this);
    application.setStatusService(new ErrorStatusService());
    childContext.getAttributes().put(NUTCH_SERVER, this);

    // Attach the application
    component.getDefaultHost().attach(application);
  }

  @Override
  public Set<Class<?>> getClasses() {
    Set<Class<?>> resources = Sets.newHashSet();

    resources.add(JobResource.class);
    resources.add(AdminResource.class);
    resources.add(ConfigResource.class);
    resources.add(DbResource.class);
    resources.add(SeedResource.class);
    resources.add(ProxyResource.class);
    resources.add(PortResource.class);
    resources.add(ServiceResource.class);

    return resources;
  }

  public ApplicationContext getSpringContext() {
    return springContext;
  }

  public ConfManager getConfManager() {
    return configManager;
  }

  public JobManager getJobManager() {
    return jobManager;
  }

  public long startTime() {
    return startTime;
  }

  public static void startServer() {
    if (!isRunning()) {
      NutchServer server = new NutchServer();
      server.start();
    }
  }

  public static Thread startInDaemonThread(Configuration conf) {
    if (!NetUtil.isMaster(conf) || NutchServer.isRunning()) {
      return null;
    }

    Thread thread = new Thread() {
      @Override
      public void run() {
        startServer();
      }
    };

    thread.setDaemon(true);
    thread.start();

    return thread;
  }

  /**
   * Convenience method to determine whether a Nutch server is running.
   * 
   * @return true if a server instance is running.
   */
  public static boolean isRunning() {
    if (running.get()) return true;

    return NetUtil.testNetwork("127.0.0.1", port);
  }

  /**
   * Starts the Nutch server printing some logging to the log file.
   */
  public void start() {
    if (isRunning()) {
      LOG.info("NutchServer is already running");
    }

    LOG.info("Starting NutchServer on port: {} with logging level: {} ...", port, logLevel);

    try {
      component.start();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot start server!", e);
    }

    LOG.info("Started NutchServer on port {}", port);
    running.set(true);
    startTime = System.currentTimeMillis();

    NutchClient client = new NutchClient(conf);
    client.register(new ServerInstance(null, port, ServerInstance.Type.NutchServer));
  }

  /**
   * Safety and convenience method to determine whether or not it is safe to
   * shut down the server. We make this assertion by consulting the
   * {@link org.apache.nutch.api.NutchApp#jobManager} for a list of jobs with
   * {@link org.apache.nutch.api.model.response.JobInfo#state} equal to
   * 'RUNNING'.
   * 
   * @param force
   *          ignore running tasks
   * 
   * @return true if there are no jobs running or false if there are jobs with
   *         running state.
   */
  public boolean canStop(boolean force) {
    if (force) {
      return true;
    }

    Collection<JobInfo> jobs = getJobManager().list(null, State.RUNNING);
    return jobs.isEmpty();
  }

  /**
   * Stop the Nutch server.
   * 
   * @param force
   *          boolean method to effectively kill jobs regardless of state.
   * @return true if no server is running or if the shutdown was successful.
   *         Return false if there are running jobs and the force switch has not
   *         been activated.
   */
  public boolean stop(boolean force) {
    if (!running.get()) {
      return true;
    }

    if (!canStop(force)) {
      LOG.warn("Running jobs - can't stop now.");
      return false;
    }

    LOG.info("Stopping NutchServer on port {}...", port);
    try {
      NutchClient client = new NutchClient(conf);
      client.unregister(new ServerInstance(null, port, ServerInstance.Type.NutchServer));

      component.stop();

      running.set(false);

      LOG.info("Stopped NutchServer on port {}", port);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot stop nutch server", e);
    }

    return true;
  }

  private static void stopRemoteServer(boolean force) {
    Reference reference = new Reference(Protocol.HTTP, LOCALHOST, port);
    reference.setPath("/admin/stop");

    if (force) {
      reference.addQueryParameter("force", "true");
    }

    ClientResource clientResource = new ClientResource(reference);
    clientResource.get();
  }

  private static Options createOptions() {
    Options options = new Options();
    OptionBuilder.hasArg();
    OptionBuilder.withArgName("logging level");
    OptionBuilder
        .withDescription("Select a logging level for the NutchServer: \n"
            + "ALL|CONFIG|FINER|FINEST|INFO|OFF|SEVERE|WARNING");
    options.addOption(OptionBuilder.create(CMD_LOG_LEVEL));

    OptionBuilder
        .withDescription("Stop running NutchServer. "
            + "true value forces the Server to stop despite running jobs e.g. kills the tasks ");
    OptionBuilder.hasOptionalArg();
    OptionBuilder.withArgName("force");
    options.addOption(OptionBuilder.create(CMD_STOP));

    OptionBuilder.withDescription("Show this help");
    options.addOption(OptionBuilder.create(CMD_HELP));

    OptionBuilder.withDescription("Port to use for restful API.");
    OptionBuilder.hasOptionalArg();
    OptionBuilder.withArgName("port number");
    options.addOption(OptionBuilder.create(CMD_PORT));
    return options;
  }

  public static void main(String[] args) throws Exception {
    CommandLineParser parser = new PosixParser();
    Options options = createOptions();
    CommandLine commandLine = parser.parse(options, args);

    if (commandLine.hasOption(CMD_HELP)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("NutchServer", options, true);
      return;
    }

    if (commandLine.hasOption(CMD_LOG_LEVEL)) {
      logLevel = commandLine.getOptionValue(CMD_LOG_LEVEL);
    }

    if (commandLine.hasOption(CMD_PORT)) {
      port = Integer.parseInt(commandLine.getOptionValue(CMD_PORT));
    }

    if (commandLine.hasOption(CMD_STOP)) {
      String stopParameter = commandLine.getOptionValue(CMD_STOP);
      boolean force = StringUtils.equals("force", stopParameter);
      stopRemoteServer(force);
      return;
    }

    startServer();
  }
}
