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
package org.apache.nutch.api.resources;

import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.apache.nutch.api.impl.PortManager;
import org.apache.nutch.fetcher.server.FetcherServer;
import org.apache.nutch.storage.local.model.ServerInstance;

import com.google.common.collect.Maps;

@Path("/port")
public class PortResource extends AbstractResource {

  private static Map<String, PortManager> portManagers = Maps.newConcurrentMap();

  static {
    portManagers.put(ServerInstance.Type.FetcherServer.name(), 
        new PortManager(ServerInstance.Type.FetcherServer, FetcherServer.BASE_PORT, FetcherServer.MAX_PORT));
  }

  @GET
  @Path("/active")
  public List<Integer> activePorts(@QueryParam("type") String type) {
    return portManagers.get(type).activePorts();
  }

  @GET
  @Path("/free")
  public List<Integer> freePorts(@QueryParam("type") String type) {
    return portManagers.get(type).freePorts();
  }

  @GET
  @Path("/acquire")
  public Integer acquire(@QueryParam("type") String type) {
    return portManagers.get(type).acquire();
  }

  @PUT
  @Path("/recycle")
  public void recycle(@QueryParam("type") String type, @QueryParam("port") String port) {
    portManagers.get(type).recycle(Integer.parseInt(port));
  }
}
